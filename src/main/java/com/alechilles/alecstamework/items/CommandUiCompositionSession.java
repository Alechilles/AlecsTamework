package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorCreateContext;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns the contributors and immutable contribution map for one open UI.
 * Dirty signals only record bounded scopes. Composition runs on the caller's
 * refresh path, which is the world-thread path in the live host.
 */
final class CommandUiCompositionSession implements AutoCloseable {
    private final Object lock = new Object();
    private final CommandUiOpenContext openContext;
    private final BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher;
    private final Runnable refreshRequest;
    private final List<State> states;
    private boolean open = true;
    private CommandUiSnapshot baseSnapshot;
    private CommandUiSnapshot currentSnapshot;

    private CommandUiCompositionSession(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest
    ) {
        this.baseSnapshot = Objects.requireNonNull(baseSnapshot, "baseSnapshot");
        this.openContext = Objects.requireNonNull(openContext, "openContext");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.refreshRequest = Objects.requireNonNull(refreshRequest,
                "refreshRequest");
        this.states = createStates(bindings);
        this.currentSnapshot = composeLocked(true);
    }

    /** Creates a session and composes every contributor once. */
    @Nonnull
    static CommandUiCompositionSession create(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest
    ) {
        return new CommandUiCompositionSession(baseSnapshot, openContext,
                bindings, publisher, refreshRequest);
    }

    @Nonnull
    CommandUiSnapshot snapshot() {
        synchronized (lock) {
            return currentSnapshot;
        }
    }

    /** Replaces the Tamework base and composes all contributors without publishing. */
    @Nonnull
    CommandUiSnapshot rebase(@Nonnull CommandUiSnapshot baseSnapshot) {
        Objects.requireNonNull(baseSnapshot, "baseSnapshot");
        synchronized (lock) {
            if (!open) return currentSnapshot;
            this.baseSnapshot = baseSnapshot;
            states.forEach(State::markAll);
            currentSnapshot = composeLocked(true);
            return currentSnapshot;
        }
    }

    /** Composes dirty contributors and publishes one immutable transition. */
    boolean refresh() {
        synchronized (lock) {
            if (!open || !hasDirty()) return false;
            currentSnapshot = composeLocked(false);
            return true;
        }
    }

    @Override
    public void close() {
        List<State> closing;
        synchronized (lock) {
            if (!open) return;
            open = false;
            closing = List.copyOf(states);
        }
        for (int index = closing.size() - 1; index >= 0; index--) {
            closeQuietly(closing.get(index).contributor);
        }
    }

    private List<State> createStates(@Nonnull List<Binding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        List<State> created = new ArrayList<>(bindings.size());
        for (Binding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            State state = new State(binding);
            state.sink = new DirtySink(state);
            try {
                state.contributor = binding.provider().create(
                        new CommandUiContributorCreateContext(
                                openContext, binding.id(), state.sink));
                if (state.contributor == null) {
                    state.failure = "contributor factory returned null";
                }
            } catch (RuntimeException | LinkageError failure) {
                state.failure = failure.getClass().getSimpleName();
            }
            state.markAll();
            created.add(state);
        }
        return List.copyOf(created);
    }

    private boolean hasDirty() {
        for (State state : states) {
            if (state.dirty()) return true;
        }
        return false;
    }

    private CommandUiSnapshot composeLocked(boolean initial) {
        Map<CommandUiContributorId, CommandUiContribution> contributions =
                new LinkedHashMap<>();
        for (State state : states) {
            if (!initial && !state.dirty()) {
                if (state.lastContribution != null) {
                    contributions.put(state.binding.id(), state.lastContribution);
                }
                continue;
            }
            state.clearDirty();
            if (!state.binding.active().getAsBoolean()) {
                if (state.lastContribution != null) {
                    contributions.put(state.binding.id(), state.lastContribution);
                }
                continue;
            }
            CommandUiContribution next = compose(state);
            state.lastContribution = next;
            contributions.put(state.binding.id(), next);
        }
        CommandUiSnapshot next = baseSnapshot.withContributions(contributions);
        if (!initial) {
            CommandUiChangeSet changes = CommandUiSnapshotDiffer.diff(
                    currentSnapshot, next);
            if (!changes.equals(CommandUiChangeSet.empty())) {
                try {
                    publisher.accept(next, changes);
                } catch (RuntimeException | LinkageError ignored) {
                    // A renderer update failure must not break contributor cleanup.
                }
            }
        }
        return next;
    }

    @Nonnull
    private CommandUiContribution compose(@Nonnull State state) {
        if (state.contributor == null) {
            return failedContribution(state);
        }
        try {
            CommandUiContribution contribution = state.contributor.compose(
                    baseSnapshot, state.lastContribution);
            if (contribution == null
                    || !state.binding.id().equals(contribution.contributorId())) {
                return failedContribution(state);
            }
            return contribution;
        } catch (RuntimeException | LinkageError failure) {
            state.failure = failure.getClass().getSimpleName();
            return failedContribution(state);
        }
    }

    @Nonnull
    private static CommandUiContribution failedContribution(@Nonnull State state) {
        CommandUiContribution.Status status = state.binding.required()
                ? CommandUiContribution.Status.REQUIRED_FAILED
                : CommandUiContribution.Status.OPTIONAL_FAILED;
        return new CommandUiContribution(state.binding.id(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), status,
                state.failure == null ? "contributor failed" : state.failure);
    }

    private void mark(@Nonnull State state, @Nonnull Runnable mutation) {
        synchronized (lock) {
            if (!open || !state.binding.active().getAsBoolean()) return;
            mutation.run();
        }
        try {
            refreshRequest.run();
        } catch (RuntimeException | LinkageError ignored) {
            // The existing refresh path owns scheduling and may be unavailable.
        }
    }

    private static void closeQuietly(@Nullable AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Continue closing all contributors in reverse order.
        }
    }

    /** One exact contributor generation selected for this session. */
    record Binding(
            @Nonnull CommandUiContributorId id,
            long generation,
            @Nonnull CommandUiContributorProvider provider,
            boolean required,
            @Nonnull BooleanSupplier active
    ) {
        Binding {
            Objects.requireNonNull(id, "id");
            if (generation < 0L) {
                throw new IllegalArgumentException(
                        "Contributor generation cannot be negative.");
            }
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(active, "active");
        }

        Binding(
                @Nonnull CommandUiContributorId id,
                long generation,
                @Nonnull CommandUiContributorProvider provider
        ) {
            this(id, generation, provider, false, () -> true);
        }
    }

    private final class State {
        private final Binding binding;
        private final Set<String> paths = new LinkedHashSet<>();
        private final Set<UUID> rows = new LinkedHashSet<>();
        private CommandUiSessionContributor contributor;
        private CommandUiContributorDirtySink sink;
        private CommandUiContribution lastContribution;
        private String failure;
        private boolean allDirty;
        private boolean pageDirty;

        private State(Binding binding) {
            this.binding = binding;
        }

        private boolean dirty() {
            return allDirty || pageDirty || !paths.isEmpty() || !rows.isEmpty();
        }

        private void markAll() {
            allDirty = true;
            pageDirty = true;
        }

        private void clearDirty() {
            allDirty = false;
            pageDirty = false;
            paths.clear();
            rows.clear();
        }
    }

    private final class DirtySink implements CommandUiContributorDirtySink {
        private final State state;

        private DirtySink(State state) {
            this.state = state;
        }

        @Override
        public void markAllDirty() {
            mark(state, state::markAll);
        }

        @Override
        public void markPageDirty() {
            mark(state, () -> state.pageDirty = true);
        }

        @Override
        public void markPathsDirty(@Nonnull Set<String> paths) {
            Objects.requireNonNull(paths, "paths");
            mark(state, () -> paths.forEach(path -> {
                if (path != null && !path.isBlank()) {
                    state.paths.add(path.trim());
                }
            }));
        }

        @Override
        public void markRowsDirty(@Nonnull Set<UUID> rowIds) {
            Objects.requireNonNull(rowIds, "rowIds");
            mark(state, () -> rowIds.forEach(row -> {
                if (row != null) state.rows.add(row);
            }));
        }
    }
}

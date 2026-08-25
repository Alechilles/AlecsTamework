package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiDiagnostics;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorCreateContext;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final RequiredFailureHandler requiredFailureHandler;
    @Nullable
    private final CommandUiDiagnosticsService diagnosticsService;
    private final long rendererGeneration;
    private final List<State> states;
    private final Map<CommandUiContributorId, CommandUiContribution>
            compatibilityContributions;
    private boolean open = true;
    private boolean diagnosticsSessionOpen;
    private CommandUiSnapshot baseSnapshot;
    private CommandUiSnapshot currentSnapshot;
    private RequiredFailure requiredFailure;

    private CommandUiCompositionSession(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull Map<CommandUiContributorId, CommandUiContribution.Status>
                    compatibilityStatuses,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest,
            @Nonnull RequiredFailureHandler requiredFailureHandler
    ) {
        this(baseSnapshot, openContext, bindings, compatibilityStatuses,
                publisher, refreshRequest, requiredFailureHandler, null, 0L);
    }

    private CommandUiCompositionSession(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull Map<CommandUiContributorId, CommandUiContribution.Status>
                    compatibilityStatuses,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest,
            @Nonnull RequiredFailureHandler requiredFailureHandler,
            @Nullable CommandUiDiagnosticsService diagnosticsService,
            long rendererGeneration
    ) {
        this.baseSnapshot = baseOnly(baseSnapshot);
        this.openContext = Objects.requireNonNull(openContext, "openContext");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.refreshRequest = Objects.requireNonNull(refreshRequest,
                "refreshRequest");
        this.requiredFailureHandler = Objects.requireNonNull(
                requiredFailureHandler, "requiredFailureHandler");
        this.diagnosticsService = diagnosticsService;
        if (rendererGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Renderer generation cannot be negative.");
        }
        this.rendererGeneration = rendererGeneration;
        this.states = createStates(bindings);
        this.compatibilityContributions = compatibilityContributions(
                compatibilityStatuses);
        try {
            openDiagnostics();
            this.currentSnapshot = composeLocked(true, true, false);
            installSubscriptions();
        } catch (RuntimeException | LinkageError failure) {
            open = false;
            closeDiagnostics("initial_composition_failed");
            closeStates(states);
            throw failure;
        }
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
                bindings, Map.of(), publisher, refreshRequest,
                (contributorId, reason) -> { });
    }

    /** Creates a session with a callback for required contributor failure. */
    @Nonnull
    static CommandUiCompositionSession create(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest,
            @Nonnull RequiredFailureHandler requiredFailureHandler
    ) {
        return new CommandUiCompositionSession(baseSnapshot, openContext,
                bindings, Map.of(), publisher, refreshRequest,
                requiredFailureHandler);
    }

    /** Creates a session with statuses produced by compatibility resolution. */
    @Nonnull
    static CommandUiCompositionSession create(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull Map<CommandUiContributorId, CommandUiContribution.Status>
                    compatibilityStatuses,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest
    ) {
        return new CommandUiCompositionSession(baseSnapshot, openContext,
                bindings, compatibilityStatuses, publisher, refreshRequest,
                (contributorId, reason) -> { });
    }

    /** Creates a status-aware session with a required-failure callback. */
    @Nonnull
    static CommandUiCompositionSession create(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull Map<CommandUiContributorId, CommandUiContribution.Status>
                    compatibilityStatuses,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest,
            @Nonnull RequiredFailureHandler requiredFailureHandler
    ) {
        return new CommandUiCompositionSession(baseSnapshot, openContext,
                bindings, compatibilityStatuses, publisher, refreshRequest,
                requiredFailureHandler);
    }

    /** Creates a session with process-local diagnostics instrumentation. */
    @Nonnull
    static CommandUiCompositionSession create(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest,
            @Nonnull CommandUiDiagnosticsService diagnosticsService,
            long rendererGeneration
    ) {
        return new CommandUiCompositionSession(baseSnapshot, openContext,
                bindings, Map.of(), publisher, refreshRequest,
                (contributorId, reason) -> { }, diagnosticsService,
                rendererGeneration);
    }

    /** Creates a status-aware instrumented session. */
    @Nonnull
    static CommandUiCompositionSession create(
            @Nonnull CommandUiSnapshot baseSnapshot,
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull List<Binding> bindings,
            @Nonnull Map<CommandUiContributorId, CommandUiContribution.Status>
                    compatibilityStatuses,
            @Nonnull BiConsumer<CommandUiSnapshot, CommandUiChangeSet> publisher,
            @Nonnull Runnable refreshRequest,
            @Nonnull RequiredFailureHandler requiredFailureHandler,
            @Nonnull CommandUiDiagnosticsService diagnosticsService,
            long rendererGeneration
    ) {
        return new CommandUiCompositionSession(baseSnapshot, openContext,
                bindings, compatibilityStatuses, publisher, refreshRequest,
                requiredFailureHandler, diagnosticsService, rendererGeneration);
    }

    @Nonnull
    CommandUiSnapshot snapshot() {
        synchronized (lock) {
            return currentSnapshot;
        }
    }

    /** Returns the current server-owned contributor action bindings. */
    @Nonnull
    List<CommandUiContributorActionBinding> actionBindings() {
        synchronized (lock) {
            List<CommandUiContributorActionBinding> bindings = new ArrayList<>();
            for (State state : states) bindings.addAll(state.actionBindings);
            return List.copyOf(bindings);
        }
    }

    boolean isOpen() {
        synchronized (lock) {
            return open;
        }
    }

    /** Returns the first required failure that ended this session, if any. */
    @Nullable
    RequiredFailure requiredFailure() {
        synchronized (lock) {
            return requiredFailure;
        }
    }

    /** Replaces the Tamework base and composes all contributors without publishing. */
    @Nonnull
    CommandUiSnapshot rebase(@Nonnull CommandUiSnapshot baseSnapshot) {
        Objects.requireNonNull(baseSnapshot, "baseSnapshot");
        RequiredCompositionFailure failure = null;
        List<State> closing = List.of();
        CommandUiSnapshot result;
        synchronized (lock) {
            if (!open) return currentSnapshot;
            this.baseSnapshot = baseOnly(baseSnapshot);
            states.forEach(State::markAll);
            try {
                currentSnapshot = composeLocked(false, false, false);
                result = currentSnapshot;
            } catch (RequiredCompositionFailure required) {
                open = false;
                requiredFailure = required.failure;
                closing = List.copyOf(states);
                result = currentSnapshot;
                failure = required;
            }
        }
        if (failure != null) {
            closeStates(closing);
            closeDiagnostics("required_composition_failed");
            notifyRequiredFailure(failure.failure);
        }
        return result;
    }

    /** Composes dirty contributors and publishes one immutable transition. */
    boolean refresh() {
        RequiredCompositionFailure failure = null;
        List<State> closing = List.of();
        synchronized (lock) {
            if (!open || !hasDirty()) return false;
            try {
                currentSnapshot = composeLocked(false, false, true);
            } catch (RequiredCompositionFailure required) {
                open = false;
                requiredFailure = required.failure;
                closing = List.copyOf(states);
                failure = required;
            }
        }
        if (failure != null) {
            closeStates(closing);
            closeDiagnostics("required_composition_failed");
            notifyRequiredFailure(failure.failure);
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        List<State> closing;
        synchronized (lock) {
            if (!open) return;
            open = false;
            closing = List.copyOf(states);
        }
        closeDiagnostics(null);
        closeStates(closing);
    }

    private List<State> createStates(@Nonnull List<Binding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        List<State> created = new ArrayList<>(bindings.size());
        Set<CommandUiContributorId> ids = new HashSet<>();
        try {
            for (Binding binding : bindings) {
                Objects.requireNonNull(binding, "binding");
                if (!ids.add(binding.id())) {
                    throw new IllegalArgumentException(
                            "Command UI contributor IDs must be unique.");
                }
                State state = new State(binding);
                state.sink = new DirtySink(state);
                try {
                    state.contributor = binding.provider().create(
                            new CommandUiContributorCreateContext(
                                    openContext, binding.id(),
                                    binding.generation(), state.sink));
                    if (state.contributor == null) {
                        state.failure = "contributor factory returned null";
                    }
                } catch (RuntimeException | LinkageError failure) {
                    state.failure = failure.getClass().getSimpleName();
                }
                state.markAll();
                created.add(state);
            }
        } catch (RuntimeException | LinkageError failure) {
            for (int index = created.size() - 1; index >= 0; index--) {
                closeQuietly(created.get(index).contributor);
            }
            throw failure;
        }
        return List.copyOf(created);
    }

    @Nonnull
    private static CommandUiSnapshot baseOnly(
            @Nonnull CommandUiSnapshot snapshot
    ) {
        return Objects.requireNonNull(snapshot, "baseSnapshot")
                .withContributions(Map.of());
    }

    private void openDiagnostics() {
        if (diagnosticsService == null) return;
        List<CommandUiDiagnostics.ContributorRegistration> selected =
                states.stream().map(state ->
                        new CommandUiDiagnostics.ContributorRegistration(
                                state.binding.id().value(),
                                state.binding.generation())).toList();
        diagnosticsService.openSession(baseSnapshot.sessionId(),
                baseSnapshot.rendererId() == null
                        ? null : baseSnapshot.rendererId().value(),
                rendererGeneration, baseSnapshot.itemId(),
                baseSnapshot.configId(), selected);
        diagnosticsSessionOpen = true;
    }

    private void closeDiagnostics(@Nullable String reason) {
        if (!diagnosticsSessionOpen || diagnosticsService == null) return;
        diagnosticsSessionOpen = false;
        diagnosticsService.closeSession(baseSnapshot.sessionId(), reason);
    }

    private void installSubscriptions() {
        for (State state : states) {
            CommandUiContributorRegistry registry = state.binding.registry();
            if (registry == null || state.binding.generation() <= 0L) continue;
            CommandUiContributorRegistry.ExactSubscription subscription =
                    registry.subscribeExactUnregister(state.binding.id(),
                            state.binding.generation(),
                            (removedId, removedGeneration) ->
                                    registrationEnded(state, removedId,
                                            removedGeneration));
            state.unregisterSubscription = subscription.handle();
            if (!subscription.active() || !state.binding.active().getAsBoolean()) {
                registrationEnded(state, state.binding.id(),
                        state.binding.generation());
            }
        }
    }

    private boolean hasDirty() {
        for (State state : states) {
            if (state.dirty() || state.registrationLost || !isActive(state)) {
                return true;
            }
        }
        return false;
    }

    private CommandUiSnapshot composeLocked(boolean initial,
                                            boolean abortRequired,
                                            boolean publish) {
        Map<CommandUiContributorId, CommandUiContribution> contributions =
                new LinkedHashMap<>(compatibilityContributions);
        for (State state : states) {
            contributions.remove(state.binding.id());
            if (!initial && !state.dirty() && isActive(state)) {
                if (state.lastPublishedContribution != null) {
                    contributions.put(state.binding.id(),
                            state.lastPublishedContribution);
                }
                continue;
            }
            CommandUiDirtyScope scope = initial
                    ? CommandUiDirtyScope.full() : state.dirtyScope;
            state.clearDirty();
            if (!isActive(state)) {
                if (initial && abortRequired && state.binding.required()) {
                    throw new InitialCompositionFailure(state.binding.id(),
                            "contributor registration generation is no longer active");
                }
                if (state.binding.required()) {
                    throw new RequiredCompositionFailure(
                            new RequiredFailure(state.binding.id(),
                                    "contributor registration generation is no longer active"));
                }
                state.lastValidContribution = null;
                state.lastPublishedContribution = null;
                state.actionBindings = List.of();
                continue;
            }
            CommandUiContribution next = compose(state, scope, initial,
                    abortRequired);
            if (next != null) {
                state.lastPublishedContribution = next;
                contributions.put(state.binding.id(), next);
            }
        }
        CommandUiSnapshot next = baseSnapshot.withContributions(contributions);
        if (!initial && publish) {
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

    @Nullable
    private CommandUiContribution compose(
            @Nonnull State state,
            @Nonnull CommandUiDirtyScope scope,
            boolean initial,
            boolean abortRequired
    ) {
        state.failure = null;
        state.lastComposeValid = false;
        state.actionBindings = List.of();
        long startedAtNanos = diagnosticsService == null
                ? 0L : diagnosticsService.compositionStarted();
        CommandUiContribution result = null;
        try {
            if (state.contributor == null) {
                state.failure = "contributor factory returned null";
                return compositionFailure(state, initial, abortRequired);
            }
            CommandUiContribution contribution = state.contributor.compose(
                    baseSnapshot, state.lastValidContribution, scope);
            if (contribution == null
                    || !state.binding.id().equals(contribution.contributorId())) {
                state.failure = contribution == null
                        ? "contributor returned null"
                        : "contributor returned a different contributor ID";
                return compositionFailure(state, initial, abortRequired);
            }
            CommandUiValueBounds.Validation bounds =
                    CommandUiValueBounds.validateContribution(contribution);
            if (!bounds.valid()) {
                state.failure = bounds.message();
                return compositionFailure(state, initial, abortRequired);
            }
            CommandUiActionCatalog.ContributorActionComposition composed =
                    new CommandUiActionCatalog().bindContributorActions(
                            state.binding.id(), state.binding.generation(),
                            contribution);
            state.lastValidContribution = contribution;
            state.actionBindings = composed.bindings();
            state.lastComposeValid = true;
            result = composed.contribution();
            return result;
        } catch (RuntimeException | LinkageError failure) {
            state.failure = failure.getClass().getSimpleName();
            return compositionFailure(state, initial, abortRequired);
        } finally {
            if (diagnosticsService != null) {
                String status = result == null
                        ? (state.binding.required()
                        ? "REQUIRED_FAILED" : "OPTIONAL_FAILED")
                        : result.status().name();
                diagnosticsService.compositionFinished(
                        baseSnapshot.sessionId(), state.binding.id().value(),
                        state.binding.generation(), startedAtNanos, status,
                        state.failure);
            }
        }
    }

    @Nullable
    private CommandUiContribution compositionFailure(
            @Nonnull State state,
            boolean initial,
            boolean abortRequired
    ) {
        if (initial && abortRequired && state.binding.required()) {
            throw new InitialCompositionFailure(state.binding.id(),
                    state.failure == null ? "required contributor failed"
                            : state.failure);
        }
        if (initial && !state.binding.required()) return null;
        if (state.binding.required()) {
            throw new RequiredCompositionFailure(new RequiredFailure(
                    state.binding.id(),
                    state.failure == null ? "required contributor failed"
                            : state.failure));
        }
        return failedContribution(state);
    }

    @Nonnull
    private static CommandUiContribution failedContribution(@Nonnull State state) {
        CommandUiContribution.Status status = state.binding.required()
                ? CommandUiContribution.Status.REQUIRED_FAILED
                : CommandUiContribution.Status.OPTIONAL_FAILED;
        if (state.lastValidContribution != null) {
            return state.lastValidContribution.withoutActions(status,
                    state.failure == null ? "contributor failed" : state.failure);
        }
        return new CommandUiContribution(state.binding.id(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), status,
                state.failure == null ? "contributor failed" : state.failure);
    }

    private void registrationEnded(
            @Nonnull State state,
            @Nonnull CommandUiContributorId removedId,
            long removedGeneration
    ) {
        if (!state.binding.id().equals(removedId)
                || state.binding.generation() != removedGeneration) return;
        AutoCloseable contributor = null;
        RequiredFailure failure = null;
        List<State> closing = List.of();
        synchronized (lock) {
            if (!open || state.registrationLost) return;
            state.registrationLost = true;
            state.clearDirty();
            state.actionBindings = List.of();
            contributor = state.contributor;
            state.contributor = null;
            if (state.binding.required()) {
                open = false;
                failure = new RequiredFailure(state.binding.id(),
                        "contributor registration was removed");
                requiredFailure = failure;
                closing = List.copyOf(states);
            } else {
                state.lastValidContribution = null;
                state.lastPublishedContribution = null;
                state.markAll();
            }
        }
        closeQuietly(contributor);
        if (failure != null) {
            closeStates(closing);
            closeDiagnostics("required_contributor_removed");
            notifyRequiredFailure(failure);
            return;
        }
        if (diagnosticsService != null) {
            diagnosticsService.contributorRemoved(baseSnapshot.sessionId(),
                    state.binding.id().value(), state.binding.generation());
        }
        requestRefresh();
    }

    private void requestRefresh() {
        try {
            refreshRequest.run();
        } catch (RuntimeException | LinkageError ignored) {
            // The existing refresh path owns scheduling and may be unavailable.
        }
    }

    private boolean isActive(@Nonnull State state) {
        if (state.registrationLost) return false;
        try {
            return state.binding.active().getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            state.failure = failure.getClass().getSimpleName();
            return false;
        }
    }

    private void notifyRequiredFailure(@Nonnull RequiredFailure failure) {
        try {
            requiredFailureHandler.failed(failure.contributorId(),
                    failure.reason());
        } catch (RuntimeException | LinkageError ignored) {
            // Failure reporting cannot keep contributor cleanup from completing.
        }
    }

    private void mark(
            @Nonnull State state,
            @Nonnull CommandUiDirtyScope scope
    ) {
        synchronized (lock) {
            if (!open || !isActive(state)) return;
            state.dirtyScope = state.dirtyScope.mergedWith(scope);
        }
        requestRefresh();
    }

    private void closeStates(@Nonnull List<State> closing) {
        for (int index = closing.size() - 1; index >= 0; index--) {
            State state = closing.get(index);
            closeQuietly(state.unregisterSubscription);
            state.unregisterSubscription = null;
            closeQuietly(state.contributor);
            state.contributor = null;
            state.actionBindings = List.of();
        }
    }

    private static void closeQuietly(@Nullable AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception | LinkageError ignored) {
            // Continue closing all contributors in reverse order.
        }
    }

    @Nonnull
    private static Map<CommandUiContributorId, CommandUiContribution>
            compatibilityContributions(
            @Nonnull Map<CommandUiContributorId, CommandUiContribution.Status>
                    statuses
    ) {
        Objects.requireNonNull(statuses, "compatibilityStatuses");
        if (statuses.isEmpty()) return Map.of();
        Map<CommandUiContributorId, CommandUiContribution> contributions =
                new LinkedHashMap<>();
        statuses.forEach((id, status) -> contributions.put(
                Objects.requireNonNull(id, "contributorId"),
                new CommandUiContribution(id, Map.of(), Map.of(), Map.of(),
                        Map.of(), Map.of(), Map.of(),
                        Objects.requireNonNull(status, "status"),
                        compatibilityReason(status))));
        return java.util.Collections.unmodifiableMap(contributions);
    }

    @Nonnull
    private static String compatibilityReason(
            @Nonnull CommandUiContribution.Status status
    ) {
        return switch (status) {
            case UNSUPPORTED_BY_RENDERER ->
                    "The selected renderer does not support this contributor.";
            case OPTIONAL_UNAVAILABLE ->
                    "The optional contributor is not registered.";
            default -> "The contributor is unavailable.";
        };
    }

    /** One exact contributor generation selected for this session. */
    record Binding(
            @Nonnull CommandUiContributorId id,
            long generation,
            @Nonnull CommandUiContributorProvider provider,
            boolean required,
            @Nonnull BooleanSupplier active,
            @Nullable CommandUiContributorRegistry registry
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
                @Nonnull CommandUiContributorProvider provider,
                boolean required,
                @Nonnull BooleanSupplier active
        ) {
            this(id, generation, provider, required, active, null);
        }

        Binding(
                @Nonnull CommandUiContributorId id,
                long generation,
                @Nonnull CommandUiContributorProvider provider
        ) {
            this(id, generation, provider, false, () -> true, null);
        }

        Binding(
                @Nonnull CommandUiContributorId id,
                long generation,
                @Nonnull CommandUiContributorProvider provider,
                boolean required,
                @Nonnull CommandUiContributorRegistry registry
        ) {
            this(id, generation, provider, required,
                    () -> registry.isActive(id, generation), registry);
        }
    }

    @FunctionalInterface
    interface RequiredFailureHandler {
        void failed(CommandUiContributorId contributorId, String reason);
    }

    record RequiredFailure(
            @Nonnull CommandUiContributorId contributorId,
            @Nonnull String reason
    ) {
        RequiredFailure {
            Objects.requireNonNull(contributorId, "contributorId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static final class RequiredCompositionFailure
            extends RuntimeException {
        private final RequiredFailure failure;

        private RequiredCompositionFailure(RequiredFailure failure) {
            super("Required contributor " + failure.contributorId().value()
                    + " failed during refresh: " + failure.reason());
            this.failure = failure;
        }
    }

    /** Signals that a required contributor could not compose the initial page. */
    static final class InitialCompositionFailure extends RuntimeException {
        InitialCompositionFailure(
                @Nonnull CommandUiContributorId contributorId,
                @Nonnull String reason
        ) {
            super("Required contributor " + contributorId.value()
                    + " failed during initial composition: " + reason);
        }
    }

    private final class State {
        private final Binding binding;
        private CommandUiSessionContributor contributor;
        private CommandUiContributorDirtySink sink;
        private CommandUiContribution lastValidContribution;
        private CommandUiContribution lastPublishedContribution;
        private String failure;
        private CommandUiDirtyScope dirtyScope = CommandUiDirtyScope.empty();
        private boolean lastComposeValid;
        private boolean registrationLost;
        private AutoCloseable unregisterSubscription;
        private List<CommandUiContributorActionBinding> actionBindings = List.of();

        private State(Binding binding) {
            this.binding = binding;
        }

        private boolean dirty() {
            return !dirtyScope.equals(CommandUiDirtyScope.empty());
        }

        private void markAll() {
            dirtyScope = dirtyScope.mergedWith(CommandUiDirtyScope.full());
        }

        private void clearDirty() {
            dirtyScope = CommandUiDirtyScope.empty();
        }
    }

    private final class DirtySink implements CommandUiContributorDirtySink {
        private final State state;

        private DirtySink(State state) {
            this.state = state;
        }

        @Override
        public void markAllDirty() {
            mark(state, CommandUiDirtyScope.full());
        }

        @Override
        public void markPageDirty() {
            mark(state, CommandUiDirtyScope.pageScope());
        }

        @Override
        public void markPathsDirty(@Nonnull Set<String> paths) {
            mark(state, CommandUiDirtyScope.paths(
                    Objects.requireNonNull(paths, "paths")));
        }

        @Override
        public void markRowsDirty(@Nonnull Set<UUID> rowIds) {
            mark(state, CommandUiDirtyScope.rows(
                    Objects.requireNonNull(rowIds, "rowIds")));
        }
    }
}

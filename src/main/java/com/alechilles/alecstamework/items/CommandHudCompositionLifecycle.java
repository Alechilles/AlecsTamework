package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorCreateContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDirtySink;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudDiagnostics;
import com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.internal.CommandHudContributorRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns one HUD session's shared state, cleanup, and registry lifecycle. */
final class CommandHudCompositionLifecycle<B, V, U> implements AutoCloseable {
    private final Object lock = new Object();
    private final UUID sessionId = UUID.randomUUID();
    private final CommandHudOpenContext openContext;
    private final CommandHudSurface surface;
    @Nullable
    private final String rendererId;
    private final long rendererGeneration;
    private final Consumer<U> publisher;
    private final Runnable refreshRequest;
    private final CommandHudCompositionSession.RequiredFailureHandler failureHandler;
    @Nullable
    private final CommandHudDiagnosticsService diagnostics;
    @Nullable
    private final CommandHudTimingWarnings timingWarnings;
    private final List<CommandHudCompositionState<B>> states;
    private final Map<CommandHudContributorId, CommandHudContribution>
            compatibilityContributions;
    private final BooleanSupplier rendererActive;
    @Nullable
    private final AutoCloseable rendererController;
    private final boolean custom;
    private final CommandHudCompositionComposer<B, V, U> composer;
    private boolean rendererClosed;
    private boolean open = true;
    private boolean initialized;
    private boolean diagnosticsSessionOpen;
    @Nullable
    private V currentView;
    @Nullable
    private U lastUpdate;
    @Nullable
    private CommandHudCompositionSession.RequiredFailure requiredFailure;

    CommandHudCompositionLifecycle(
            @Nonnull CommandHudOpenContext openContext,
            @Nonnull CommandHudSurface surface,
            @Nullable String rendererId,
            long rendererGeneration,
            @Nonnull CommandHudCompositionSupport.SurfaceAdapter<B, V, U> adapter,
            @Nonnull List<CommandHudCompositionBinding<B>> bindings,
            @Nonnull Map<CommandHudContributorId, CommandHudContribution> compatibility,
            boolean custom,
            @Nonnull BooleanSupplier rendererActive,
            @Nullable Supplier<? extends AutoCloseable> rendererFactory,
            @Nullable CommandHudDiagnosticsService diagnostics,
            @Nullable CommandHudTimingWarnings timingWarnings,
            @Nullable Consumer<U> publisher,
            @Nullable Runnable refreshRequest,
            @Nullable CommandHudCompositionSession.RequiredFailureHandler failureHandler
    ) {
        this.openContext = Objects.requireNonNull(openContext, "openContext");
        this.surface = Objects.requireNonNull(surface, "surface");
        this.rendererId = rendererId == null || rendererId.isBlank() ? null : rendererId.trim();
        if (rendererGeneration < 0L) {
            throw new IllegalArgumentException("Renderer generation cannot be negative.");
        }
        this.rendererGeneration = rendererGeneration;
        this.publisher = publisher == null ? ignored -> { } : publisher;
        this.refreshRequest = refreshRequest == null ? () -> { } : refreshRequest;
        this.failureHandler = failureHandler == null ? (id, reason) -> { } : failureHandler;
        this.diagnostics = diagnostics;
        this.timingWarnings = timingWarnings;
        this.rendererActive = Objects.requireNonNull(rendererActive, "rendererActive");

        AutoCloseable createdRenderer = null;
        boolean rendererReady = custom;
        if (custom && rendererFactory != null) {
            try {
                createdRenderer = rendererFactory.get();
                rendererReady = createdRenderer != null;
            } catch (RuntimeException | LinkageError ignored) {
                rendererReady = false;
            }
        }
        if (!rendererReady) closeQuietly(createdRenderer);
        this.rendererController = createdRenderer;
        this.custom = rendererReady;
        Map<CommandHudContributorId, CommandHudContribution> copied = copyContributions(
                compatibility, rendererReady);
        this.compatibilityContributions = copied;
        this.states = rendererReady ? createStates(bindings) : List.of();
        try {
            this.composer = new CommandHudCompositionComposer<>(adapter, states, copied,
                    rendererReady, rendererActive, sessionId, diagnostics, timingWarnings);
            if (rendererReady) {
                openDiagnostics();
                installSubscriptions();
            }
        } catch (RuntimeException | LinkageError failure) {
            closeDiagnostics("initial_composition_failed");
            closeStates(rendererReady ? states : List.of());
            closeQuietly(createdRenderer);
            throw failure;
        }
    }

    @Nonnull
    V compose(@Nonnull B base) {
        Objects.requireNonNull(base, "base");
        CommandHudCompositionSession.InitialCompositionFailure failure = null;
        List<CommandHudCompositionState<B>> closing = List.of();
        V result = null;
        synchronized (lock) {
            if (!open && currentView != null) return currentView;
            try {
                result = composer.compose(base, true).view();
                currentView = result;
                initialized = true;
            } catch (CommandHudCompositionSession.RequiredCompositionFailure required) {
                open = false;
                requiredFailure = required.failure;
                closing = List.copyOf(states);
                failure = new CommandHudCompositionSession.InitialCompositionFailure(
                        required.failure.contributorId(), required.failure.reason());
            } catch (CommandHudCompositionComposer.RendererUnavailableFailure unavailable) {
                open = false;
                closing = List.copyOf(states);
                failure = new CommandHudCompositionSession.InitialCompositionFailure(
                        unavailable.getMessage());
            }
        }
        if (failure != null) {
            closeStates(closing);
            closeDiagnostics("initial_composition_failed");
            closeRenderer();
            notifyRequiredFailure(requiredFailure);
            throw failure;
        }
        return result;
    }

    @Nonnull
    V view() {
        synchronized (lock) {
            if (currentView == null) throw new IllegalStateException(
                    "HUD composition has not started.");
            return currentView;
        }
    }

    @Nonnull
    V snapshot() {
        return view();
    }

    @Nullable
    U refresh(@Nonnull B base) {
        Objects.requireNonNull(base, "base");
        CommandHudCompositionSession.RequiredCompositionFailure failure = null;
        List<CommandHudCompositionState<B>> closing = List.of();
        U update = null;
        synchronized (lock) {
            if (!open || !initialized || !composer.hasDirty()) return null;
            try {
                V previous = currentView;
                CommandHudCompositionComposer.Composition<V> composed =
                        composer.compose(base, false);
                currentView = composed.view();
                update = ((CommandHudCompositionSupport.SurfaceAdapter<B, V, U>)
                        getAdapter()).update(composed.view(), previous, composed.changeData());
                lastUpdate = update;
            } catch (CommandHudCompositionSession.RequiredCompositionFailure required) {
                open = false;
                requiredFailure = required.failure;
                closing = List.copyOf(states);
                failure = required;
            } catch (CommandHudCompositionComposer.RendererUnavailableFailure unavailable) {
                open = false;
                closing = List.copyOf(states);
            }
        }
        if (failure != null || !closing.isEmpty() && !open) {
            closeStates(closing);
            closeDiagnostics("required_composition_failed");
            closeRenderer();
            if (failure != null) notifyRequiredFailure(failure.failure);
            return null;
        }
        if (update != null) publish(update);
        return update;
    }

    @Nonnull
    private CommandHudCompositionSupport.SurfaceAdapter<B, V, U> getAdapter() {
        return composer.adapter();
    }

    @Nonnull
    V rebase(@Nonnull B base) {
        Objects.requireNonNull(base, "base");
        synchronized (lock) {
            if (!initialized) return compose(base);
            for (CommandHudCompositionState<B> state : states) state.dirty.markAll();
        }
        refresh(base);
        return view();
    }

    @Nullable
    U lastUpdate() {
        synchronized (lock) {
            return lastUpdate;
        }
    }

    @Nullable
    AutoCloseable rendererController() {
        return rendererController;
    }

    boolean custom() { return custom; }

    @Nonnull
    CommandHudSurface surface() { return surface; }

    @Nonnull
    UUID sessionId() { return sessionId; }

    boolean isOpen() {
        synchronized (lock) { return open; }
    }

    @Nullable
    CommandHudCompositionSession.RequiredFailure requiredFailure() {
        synchronized (lock) { return requiredFailure; }
    }

    @Nonnull
    CommandHudContributorDirtySink contributorSink(@Nonnull CommandHudContributorId id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            for (CommandHudCompositionState<B> state : states) {
                if (state.binding.id().equals(id)) return state.sink;
            }
        }
        return CommandHudContributorDirtySink.noop();
    }

    void markPathsDirty(@Nonnull CommandHudContributorId id, @Nonnull Set<String> paths) {
        contributorSink(id).markPathsDirty(paths);
    }

    void markAllDirty(@Nonnull CommandHudContributorId id) {
        contributorSink(id).markAllDirty();
    }

    @Override
    public void close() {
        List<CommandHudCompositionState<B>> closing;
        synchronized (lock) {
            if (!open && rendererClosed) return;
            open = false;
            closing = List.copyOf(states);
        }
        closeDiagnostics(null);
        closeStates(closing);
        closeRenderer();
    }

    @Nonnull
    private List<CommandHudCompositionState<B>> createStates(
            @Nonnull List<CommandHudCompositionBinding<B>> bindings
    ) {
        Objects.requireNonNull(bindings, "bindings");
        List<CommandHudCompositionState<B>> created = new ArrayList<>(bindings.size());
        Set<CommandHudContributorId> ids = new HashSet<>();
        try {
            for (CommandHudCompositionBinding<B> binding : bindings) {
                Objects.requireNonNull(binding, "binding");
                if (!ids.add(binding.id())) throw new IllegalArgumentException(
                        "HUD contributor IDs must be unique.");
                CommandHudCompositionState<B> state = new CommandHudCompositionState<>(
                        binding, this::mark);
                try {
                    state.contributor = binding.factory().create(new CommandHudContributorCreateContext(
                            openContext, binding.id(), binding.generation(), state.sink));
                    if (state.contributor == null) state.failure = "contributor factory returned null";
                } catch (RuntimeException | LinkageError failure) {
                    state.failure = failure.getClass().getSimpleName();
                }
                state.dirty.markAll();
                created.add(state);
            }
        } catch (RuntimeException | LinkageError failure) {
            closeStates(created);
            throw failure;
        }
        return List.copyOf(created);
    }

    private void openDiagnostics() {
        if (diagnostics == null || !custom) return;
        List<CommandHudDiagnostics.ContributorRegistration> selected = new ArrayList<>();
        for (CommandHudCompositionState<B> state : states) selected.add(
                new CommandHudDiagnostics.ContributorRegistration(
                        state.binding.id().value(), state.binding.generation()));
        for (CommandHudContribution contribution : compatibilityContributions.values()) {
            selected.add(new CommandHudDiagnostics.ContributorRegistration(
                    contribution.contributorId().value(), 0L));
        }
        diagnostics.openSession(sessionId, surface, rendererId, rendererGeneration,
                openContext.itemId(), openContext.configId(), selected);
        diagnosticsSessionOpen = true;
    }

    private void closeDiagnostics(@Nullable String reason) {
        if (!diagnosticsSessionOpen || diagnostics == null) return;
        diagnosticsSessionOpen = false;
        diagnostics.closeSession(sessionId, reason);
    }

    private void installSubscriptions() {
        for (CommandHudCompositionState<B> state : states) {
            CommandHudContributorRegistry registry = state.binding.registry();
            if (registry == null || state.binding.generation() <= 0L) continue;
            CommandHudContributorRegistry.ExactSubscription subscription = state.binding.target()
                    ? registry.subscribeExactTargetUnregister(state.binding.id(),
                    state.binding.generation(), (id, generation) -> registrationEnded(state, id, generation))
                    : registry.subscribeExactHotswapUnregister(state.binding.id(),
                    state.binding.generation(), (id, generation) -> registrationEnded(state, id, generation));
            state.unregisterSubscription = subscription.handle();
            if (!subscription.active() || !isActive(state)) {
                registrationEnded(state, state.binding.id(), state.binding.generation());
            }
        }
    }

    private void registrationEnded(
            @Nonnull CommandHudCompositionState<B> state,
            @Nonnull CommandHudContributorId id,
            long generation
    ) {
        if (!state.binding.id().equals(id) || state.binding.generation() != generation) return;
        AutoCloseable contributor = null;
        CommandHudCompositionSession.RequiredFailure failure = null;
        List<CommandHudCompositionState<B>> closing = List.of();
        synchronized (lock) {
            if (!open || state.registrationLost) return;
            state.registrationLost = true;
            state.dirty.clear();
            contributor = state.contributor;
            state.contributor = null;
            if (state.binding.required()) {
                open = false;
                failure = new CommandHudCompositionSession.RequiredFailure(
                        state.binding.id(), "contributor registration was removed");
                requiredFailure = failure;
                closing = List.copyOf(states);
            } else {
                state.lastValidContribution = null;
                state.lastPublishedContribution = null;
                state.dirty.markAll();
            }
        }
        closeQuietly(contributor);
        if (failure != null) {
            closeStates(closing);
            closeDiagnostics("required_contributor_removed");
            closeRenderer();
            notifyRequiredFailure(failure);
        } else {
            if (diagnostics != null) diagnostics.contributorRemoved(
                    sessionId, id.value(), generation);
            requestRefresh();
        }
    }

    private void mark(
            @Nonnull CommandHudCompositionState<B> state,
            @Nonnull CommandHudDirtyScope scope
    ) {
        synchronized (lock) {
            if (!open || !isActive(state)) return;
            if (scope.fullRefresh()) state.dirty.markAll();
            else state.dirty.markPaths(scope.paths());
        }
        requestRefresh();
    }

    private boolean isActive(@Nonnull CommandHudCompositionState<B> state) {
        if (state.registrationLost) return false;
        try {
            return state.binding.active().getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            state.failure = failure.getClass().getSimpleName();
            return false;
        }
    }

    private void requestRefresh() {
        try { refreshRequest.run(); } catch (RuntimeException | LinkageError ignored) { }
    }

    private void publish(@Nonnull U update) {
        try { publisher.accept(update); } catch (RuntimeException | LinkageError ignored) { }
    }

    private void notifyRequiredFailure(
            @Nullable CommandHudCompositionSession.RequiredFailure failure
    ) {
        if (failure == null) return;
        try { failureHandler.failed(failure.contributorId(), failure.reason()); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private void closeStates(@Nonnull List<CommandHudCompositionState<B>> closing) {
        for (int index = closing.size() - 1; index >= 0; index--) {
            CommandHudCompositionState<B> state = closing.get(index);
            closeQuietly(state.unregisterSubscription);
            state.unregisterSubscription = null;
            closeQuietly(state.contributor);
            state.contributor = null;
        }
    }

    private void closeRenderer() {
        AutoCloseable controller;
        synchronized (lock) {
            if (rendererClosed) return;
            rendererClosed = true;
            controller = rendererController;
        }
        closeQuietly(controller);
    }

    private static void closeQuietly(@Nullable AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception | LinkageError ignored) { }
    }

    @Nonnull
    private static Map<CommandHudContributorId, CommandHudContribution> copyContributions(
            @Nonnull Map<CommandHudContributorId, CommandHudContribution> source,
            boolean custom
    ) {
        if (!custom || source.isEmpty()) return Map.of();
        LinkedHashMap<CommandHudContributorId, CommandHudContribution> copy = new LinkedHashMap<>();
        source.forEach((id, contribution) -> {
            if (id == null) return;
            CommandHudContribution value = Objects.requireNonNull(contribution, "contribution");
            if (!id.equals(value.contributorId())) throw new IllegalArgumentException(
                    "Contribution key must match contributor ID.");
            copy.put(id, value);
        });
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }
}

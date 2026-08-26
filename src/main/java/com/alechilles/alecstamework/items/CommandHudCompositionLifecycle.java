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
import com.alechilles.alecstamework.api.internal.CommandHudRendererRegistry;
import java.util.ArrayList;
import java.util.HashSet;
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
    @Nullable
    private final CommandHudRendererRegistry rendererRegistry;
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
    @Nonnull
    private final CommandHudCompositionCleanup<B> cleanup;
    private final boolean custom;
    private final CommandHudCompositionComposer<B, V, U> composer;
    private boolean cleanupStarted;
    private boolean requiredFailureNotified;
    private long lifecycleVersion;
    private boolean open = true;
    private boolean initialized;
    @Nullable
    private V currentView;
    @Nullable
    private U lastUpdate;
    @Nullable
    private CommandHudCompositionSession.RequiredFailure requiredFailure;
    @Nullable
    private CommandHudCompositionSession.InitialCompositionFailure initialFailure;

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
            @Nullable CommandHudRendererRegistry rendererRegistry,
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
        this.rendererRegistry = rendererRegistry;
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
        this.custom = rendererReady;
        Map<CommandHudContributorId, CommandHudContribution> copied =
                CommandHudCompositionSupport.copyContributions(
                compatibility, rendererReady);
        this.compatibilityContributions = copied;
        this.states = rendererReady ? createStates(bindings) : List.of();
        this.cleanup = new CommandHudCompositionCleanup<>(states, diagnostics,
                sessionId, createdRenderer);
        try {
            this.composer = new CommandHudCompositionComposer<>(adapter, states, copied,
                    rendererReady, rendererActive, sessionId, diagnostics, timingWarnings);
            if (rendererReady) {
                openDiagnostics();
                installSubscriptions();
            }
        } catch (RuntimeException | LinkageError failure) {
            cleanup.close("initial_composition_failed");
            throw failure;
        }
}
    @Nonnull
    V compose(@Nonnull B base) {
        Objects.requireNonNull(base, "base");
        CommandHudCompositionSession.InitialCompositionFailure failure = null;
        boolean cleanup = false;
        V result = null;
        synchronized (lock) {
            if (!open) {
                if (initialFailure != null) throw initialFailure;
                if (currentView != null) return currentView;
                initialFailure = new CommandHudCompositionSession.InitialCompositionFailure(
                        "HUD renderer registration is no longer active");
                throw initialFailure;
            }
            long version = lifecycleVersion;
            try {
                result = composer.compose(base, true).view();
                if (!isCurrent(version)) {
                    if (requiredFailure != null) {
                        failure = new CommandHudCompositionSession.InitialCompositionFailure(
                                requiredFailure.contributorId(), requiredFailure.reason());
                        initialFailure = failure;
                    } else {
                        failure = new CommandHudCompositionSession.InitialCompositionFailure(
                                "HUD renderer registration is no longer active");
                        initialFailure = failure;
                    }
                } else {
                    currentView = result;
                    initialized = true;
                }
            } catch (CommandHudCompositionSession.RequiredCompositionFailure required) {
                requiredFailure = required.failure;
                failure = new CommandHudCompositionSession.InitialCompositionFailure(
                        required.failure.contributorId(), required.failure.reason());
                initialFailure = failure;
                terminateLocked();
                cleanup = true;
            } catch (CommandHudCompositionComposer.RendererUnavailableFailure unavailable) {
                failure = new CommandHudCompositionSession.InitialCompositionFailure(
                        unavailable.getMessage());
                initialFailure = failure;
                terminateLocked();
                cleanup = true;
            }
        }
        if (cleanup) {
            cleanup("initial_composition_failed");
            notifyRequiredFailure(requiredFailure);
            throw failure;
        }
        if (failure != null) throw failure;
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
        boolean cleanup = false;
        long publishVersion = -1L;
        U update = null;
        synchronized (lock) {
            if (!open || !initialized || !composer.hasDirty()) return null;
            long version = lifecycleVersion;
            try {
                V previous = currentView;
                CommandHudCompositionComposer.Composition<V> composed =
                        composer.compose(base, false);
                if (!isCurrent(version)) return null;
                currentView = composed.view();
                update = ((CommandHudCompositionSupport.SurfaceAdapter<B, V, U>)
                        getAdapter()).update(composed.view(), previous, composed.changeData());
                if (!isCurrent(version)) return null;
                lastUpdate = update;
                publishVersion = version;
            } catch (CommandHudCompositionSession.RequiredCompositionFailure required) {
                requiredFailure = required.failure;
                failure = required;
                terminateLocked();
                cleanup = true;
            } catch (CommandHudCompositionComposer.RendererUnavailableFailure unavailable) {
                terminateLocked();
                cleanup = true;
            }
        }
        if (cleanup) {
            cleanup(failure == null
                    ? "renderer_unavailable" : "required_composition_failed");
            if (failure != null) notifyRequiredFailure(failure.failure);
            return null;
        }
        if (update != null) publish(update, publishVersion);
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
        return cleanup.rendererController();
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
        synchronized (lock) {
            terminateLocked();
        }
        cleanup(null);
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
            CommandHudCompositionCleanup.closeStates(created);
            throw failure;
        }
        return List.copyOf(created);
    }

    private void openDiagnostics() {
        List<CommandHudDiagnostics.ContributorRegistration> selected = new ArrayList<>();
        for (CommandHudCompositionState<B> state : states) selected.add(
                new CommandHudDiagnostics.ContributorRegistration(
                        state.binding.id().value(), state.binding.generation()));
        for (CommandHudContribution contribution : compatibilityContributions.values()) {
            selected.add(new CommandHudDiagnostics.ContributorRegistration(
                    contribution.contributorId().value(), 0L));
        }
        cleanup.openDiagnostics(surface, rendererId, rendererGeneration,
                openContext, selected);
    }

    private void installSubscriptions() {
        synchronized (lock) {
            CommandHudCompositionRendererBinding.install(rendererRegistry, surface, rendererId,
                    rendererGeneration, this::rendererActive, this::rendererRegistrationEnded,
                    cleanup::setRendererSubscription);
            if (!open) return;
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
                if (!open) return;
            }
        }
    }

    private void rendererRegistrationEnded() {
        synchronized (lock) {
            if (!open) return;
            terminateLocked();
        }
        cleanup("renderer_unavailable");
    }

    private void registrationEnded(
            @Nonnull CommandHudCompositionState<B> state,
            @Nonnull CommandHudContributorId id,
            long generation
    ) {
        if (!state.binding.id().equals(id) || state.binding.generation() != generation) return;
        AutoCloseable contributor = null;
        CommandHudCompositionSession.RequiredFailure failure = null;
        synchronized (lock) {
            if (!open || state.registrationLost) return;
            state.registrationLost = true;
            state.dirty.clear();
            contributor = state.contributor;
            state.contributor = null;
            lifecycleVersion++;
            if (state.binding.required()) {
                failure = new CommandHudCompositionSession.RequiredFailure(
                        state.binding.id(), "contributor registration was removed");
                requiredFailure = failure;
                terminateLocked();
            } else {
                state.lastValidContribution = null;
                state.lastPublishedContribution = null;
                state.unavailablePublished = false;
                state.dirty.markAll();
            }
        }
        closeQuietly(contributor);
        if (failure != null) {
            cleanup("required_contributor_removed");
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

    private boolean isCurrent(long version) {
        return open && lifecycleVersion == version
                && (!custom || rendererActive()) && composer.contributorsCurrent();
    }

    private boolean rendererActive() {
        try {
            return custom && rendererActive.getAsBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nonnull
    private void terminateLocked() {
        if (cleanupStarted) return;
        open = false;
        lifecycleVersion++;
        cleanupStarted = true;
    }

    private void cleanup(@Nullable String reason) {
        cleanup.close(reason);
    }

    private void publish(@Nonnull U update, long version) {
        synchronized (lock) {
            if (!isCurrent(version)) return;
            try { publisher.accept(update); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private void notifyRequiredFailure(
            @Nullable CommandHudCompositionSession.RequiredFailure failure
    ) {
        synchronized (lock) {
            if (failure == null || requiredFailureNotified) return;
            requiredFailureNotified = true;
        }
        try { failureHandler.failed(failure.contributorId(), failure.reason()); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    private static void closeQuietly(@Nullable AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception | LinkageError ignored) { }
    }

}

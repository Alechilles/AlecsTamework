package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDirtySink;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudRendererProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudRendererProvider;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed facade for one lifecycle-bound command HUD composition. */
final class CommandHudCompositionSession<B, V, U> implements AutoCloseable {
    private final CommandHudCompositionLifecycle<B, V, U> lifecycle;

    private CommandHudCompositionSession(
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHudSurface surface,
            @Nullable String rendererId,
            long rendererGeneration,
            @Nonnull CommandHudCompositionSupport.SurfaceAdapter<B, V, U> adapter,
            @Nonnull List<CommandHudCompositionBinding<B>> bindings,
            @Nonnull java.util.Map<CommandHudContributorId,
                    com.alechilles.alecstamework.api.commandhud.CommandHudContribution> contributions,
            boolean custom,
            @Nonnull java.util.function.BooleanSupplier rendererActive,
            @Nullable Supplier<? extends AutoCloseable> rendererFactory,
            @Nullable CommandHudDiagnosticsService diagnostics,
            @Nullable CommandHudTimingWarnings timingWarnings,
            @Nullable Consumer<U> publisher,
            @Nullable Runnable refreshRequest,
            @Nullable RequiredFailureHandler failureHandler
    ) {
        lifecycle = new CommandHudCompositionLifecycle<>(context, surface, rendererId,
                rendererGeneration, adapter, bindings, contributions, custom,
                rendererActive, rendererFactory, diagnostics, timingWarnings,
                publisher, refreshRequest, failureHandler);
    }

    /** Opens a target session using exact renderer and contributor generations. */
    @Nonnull
    static CommandHudCompositionSession<CommandTargetHudSnapshot,
            CommandTargetHudView, CommandTargetHudUpdate> target(
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHudTargetResolution resolution,
            @Nullable CommandHudDiagnosticsService diagnostics,
            @Nullable CommandHudTimingWarnings timingWarnings
    ) {
        return target(context, resolution, diagnostics, timingWarnings,
                null, null, null);
    }

    /** Opens an instrumented target session. */
    @Nonnull
    static CommandHudCompositionSession<CommandTargetHudSnapshot,
            CommandTargetHudView, CommandTargetHudUpdate> target(
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHudTargetResolution resolution,
            @Nullable CommandHudDiagnosticsService diagnostics,
            @Nullable CommandHudTimingWarnings timingWarnings,
            @Nullable Consumer<CommandTargetHudUpdate> publisher,
            @Nullable Runnable refreshRequest,
            @Nullable RequiredFailureHandler failureHandler
    ) {
        Objects.requireNonNull(resolution, "resolution");
        CommandTargetHudRendererProvider provider = resolution.rendererProvider();
        Supplier<AutoCloseable> factory = provider == null ? null : () -> provider.create(context);
        return new CommandHudCompositionSession<>(context, CommandHudSurface.TARGET,
                resolution.rendererId() == null ? null : resolution.rendererId().value(),
                resolution.rendererGeneration(), CommandHudCompositionSupport.TARGET_ADAPTER,
                resolution.bindings(), resolution.contributions(), resolution.custom(),
                resolution::rendererActive, factory, diagnostics, timingWarnings,
                publisher, refreshRequest, failureHandler);
    }

    /** Opens a hotswap session using exact renderer and contributor generations. */
    @Nonnull
    static CommandHudCompositionSession<CommandHotswapHudSnapshot,
            CommandHotswapHudView, CommandHotswapHudUpdate> hotswap(
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHudHotswapResolution resolution,
            @Nullable CommandHudDiagnosticsService diagnostics,
            @Nullable CommandHudTimingWarnings timingWarnings
    ) {
        return hotswap(context, resolution, diagnostics, timingWarnings,
                null, null, null);
    }

    /** Opens an instrumented hotswap session. */
    @Nonnull
    static CommandHudCompositionSession<CommandHotswapHudSnapshot,
            CommandHotswapHudView, CommandHotswapHudUpdate> hotswap(
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHudHotswapResolution resolution,
            @Nullable CommandHudDiagnosticsService diagnostics,
            @Nullable CommandHudTimingWarnings timingWarnings,
            @Nullable Consumer<CommandHotswapHudUpdate> publisher,
            @Nullable Runnable refreshRequest,
            @Nullable RequiredFailureHandler failureHandler
    ) {
        Objects.requireNonNull(resolution, "resolution");
        CommandHotswapHudRendererProvider provider = resolution.rendererProvider();
        Supplier<AutoCloseable> factory = provider == null ? null : () -> provider.create(context);
        return new CommandHudCompositionSession<>(context, CommandHudSurface.HOTSWAP,
                resolution.rendererId() == null ? null : resolution.rendererId().value(),
                resolution.rendererGeneration(), CommandHudCompositionSupport.HOTSWAP_ADAPTER,
                resolution.bindings(), resolution.contributions(), resolution.custom(),
                resolution::rendererActive, factory, diagnostics, timingWarnings,
                publisher, refreshRequest, failureHandler);
    }

    @Nonnull
    V compose(@Nonnull B base) { return lifecycle.compose(base); }

    @Nonnull
    V view() { return lifecycle.view(); }

    @Nonnull
    V snapshot() { return lifecycle.snapshot(); }

    @Nullable
    U refresh(@Nonnull B base) { return lifecycle.refresh(base); }

    @Nonnull
    V rebase(@Nonnull B base) { return lifecycle.rebase(base); }

    @Nullable
    U lastUpdate() { return lifecycle.lastUpdate(); }

    @Nullable
    CommandTargetHudController targetController() {
        return lifecycle.rendererController() instanceof CommandTargetHudController controller
                ? controller : null;
    }

    @Nullable
    CommandHotswapHudController hotswapController() {
        return lifecycle.rendererController() instanceof CommandHotswapHudController controller
                ? controller : null;
    }

    boolean custom() { return lifecycle.custom(); }

    @Nonnull
    CommandHudSurface surface() { return lifecycle.surface(); }

    @Nonnull
    UUID sessionId() { return lifecycle.sessionId(); }

    boolean isOpen() { return lifecycle.isOpen(); }

    @Nullable
    RequiredFailure requiredFailure() { return lifecycle.requiredFailure(); }

    @Nonnull
    CommandHudContributorDirtySink contributorSink(@Nonnull CommandHudContributorId id) {
        return lifecycle.contributorSink(id);
    }

    void markPathsDirty(@Nonnull CommandHudContributorId id, @Nonnull Set<String> paths) {
        lifecycle.markPathsDirty(id, paths);
    }

    void markAllDirty(@Nonnull CommandHudContributorId id) {
        lifecycle.markAllDirty(id);
    }

    @Override
    public void close() { lifecycle.close(); }

    @FunctionalInterface
    interface RequiredFailureHandler {
        void failed(@Nonnull CommandHudContributorId contributorId, @Nonnull String reason);
    }

    record RequiredFailure(
            @Nonnull CommandHudContributorId contributorId,
            @Nonnull String reason
    ) {
        RequiredFailure {
            Objects.requireNonNull(contributorId, "contributorId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Signals that a required contributor failed initial composition. */
    static final class InitialCompositionFailure extends RuntimeException {
        InitialCompositionFailure(
                @Nonnull CommandHudContributorId contributorId,
                @Nonnull String reason
        ) {
            super("Required HUD contributor " + contributorId.value()
                    + " failed during initial composition: " + reason);
        }

        InitialCompositionFailure(@Nonnull String reason) {
            super("HUD renderer failed during initial composition: " + reason);
        }
    }

    static final class RequiredCompositionFailure extends RuntimeException {
        final RequiredFailure failure;

        RequiredCompositionFailure(@Nonnull RequiredFailure failure) {
            super(failure.reason());
            this.failure = Objects.requireNonNull(failure, "failure");
        }
    }
}

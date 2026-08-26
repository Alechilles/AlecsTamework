package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudApi;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererId;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Runs detached target and hotswap command HUD composition checks. */
public final class CommandHudSelfTestRuntime {
    private CommandHudSelfTestRuntime() {
    }

    /** Runs both HUD composition paths without creating a live player HUD. */
    @Nonnull
    public static CommandHudSelfTestResult run(
            @Nonnull CommandHudApi commandHud,
            @Nullable UUID playerUuid,
            @Nonnull String targetRendererId,
            @Nonnull String targetContributorId,
            @Nonnull String hotswapRendererId,
            @Nonnull String hotswapContributorId
    ) {
        if (!(commandHud instanceof CommandHudRegistry registry) || !registry.available()) {
            return CommandHudSelfTestResult.unavailable();
        }
        CommandHudCompositionResolver resolver =
                new CommandHudCompositionResolver(registry, false);
        UUID ownerUuid = playerUuid == null ? UUID.randomUUID() : playerUuid;
        CommandHudContributorId targetId = CommandHudContributorId.of(targetContributorId);
        CommandHudContributorId hotswapId = CommandHudContributorId.of(hotswapContributorId);
        SurfaceSelfTestResult target = runTarget(
                resolver, ownerUuid, targetRendererId, targetId);
        SurfaceSelfTestResult hotswap = runHotswap(
                resolver, ownerUuid, hotswapRendererId, hotswapId);
        return new CommandHudSelfTestResult(
                target.rendererCreated(), target.contributionReady(), target.focusedRefresh(),
                target.sessionClosed(), hotswap.rendererCreated(),
                hotswap.contributionReady(), hotswap.focusedRefresh(), hotswap.sessionClosed());
    }

    @Nonnull
    private static SurfaceSelfTestResult runTarget(
            @Nonnull CommandHudCompositionResolver resolver,
            @Nonnull UUID ownerUuid,
            @Nonnull String rendererId,
            @Nonnull CommandHudContributorId contributorId
    ) {
        CommandHudOpenContext context = new CommandHudOpenContext(
                ownerUuid, "en-US", "selftest:command", "selftest:command",
                "selftest:config", CommandHudSurface.TARGET,
                CommandHudRendererId.of(rendererId), ownerUuid,
                "selftest-target", 1L);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                rendererId, List.of(new CommandHudContributorRequirement(contributorId, true)));
        CommandHudCompositionSession<CommandTargetHudSnapshot, CommandTargetHudView,
                CommandTargetHudUpdate> session = CommandHudCompositionSession.target(
                context, resolution, resolver.diagnostics, resolver.timingWarnings);
        try {
            CommandTargetHudSnapshot snapshot = selfTestTargetSnapshot(ownerUuid);
            CommandTargetHudView view = session.compose(snapshot);
            boolean rendererCreated = session.targetController() != null;
            boolean contributionReady = view.contribution(contributorId) != null
                    && view.contribution(contributorId).available();
            session.markPathsDirty(contributorId, Set.of("probe/value"));
            CommandTargetHudUpdate update = session.refresh(snapshot);
            boolean focusedRefresh = update != null && !update.fullRefresh()
                    && update.changeSet().pathsFor(contributorId).contains("probe/value");
            return new SurfaceSelfTestResult(rendererCreated, contributionReady,
                    focusedRefresh, closeSession(session));
        } catch (RuntimeException | LinkageError failure) {
            closeSession(session);
            return SurfaceSelfTestResult.failed();
        }
    }

    @Nonnull
    private static SurfaceSelfTestResult runHotswap(
            @Nonnull CommandHudCompositionResolver resolver,
            @Nonnull UUID ownerUuid,
            @Nonnull String rendererId,
            @Nonnull CommandHudContributorId contributorId
    ) {
        CommandHudOpenContext context = new CommandHudOpenContext(
                ownerUuid, "en-US", "selftest:command", "selftest:command",
                "selftest:config", CommandHudSurface.HOTSWAP,
                CommandHudRendererId.of(rendererId), null, null, 2L);
        CommandHudHotswapResolution resolution = resolver.resolveHotswap(
                rendererId, List.of(new CommandHudContributorRequirement(contributorId, true)));
        CommandHudCompositionSession<CommandHotswapHudSnapshot, CommandHotswapHudView,
                CommandHotswapHudUpdate> session = CommandHudCompositionSession.hotswap(
                context, resolution, resolver.diagnostics, resolver.timingWarnings);
        try {
            CommandHotswapHudSnapshot snapshot = selfTestHotswapSnapshot();
            CommandHotswapHudView view = session.compose(snapshot);
            boolean rendererCreated = session.hotswapController() != null;
            boolean contributionReady = view.contribution(contributorId) != null
                    && view.contribution(contributorId).available();
            session.markPathsDirty(contributorId, Set.of("probe/value"));
            CommandHotswapHudUpdate update = session.refresh(snapshot);
            boolean focusedRefresh = update != null && !update.fullRefresh()
                    && update.changeSet().pathsFor(contributorId).contains("probe/value");
            return new SurfaceSelfTestResult(rendererCreated, contributionReady,
                    focusedRefresh, closeSession(session));
        } catch (RuntimeException | LinkageError failure) {
            closeSession(session);
            return SurfaceSelfTestResult.failed();
        }
    }

    private static boolean closeSession(
            @Nonnull CommandHudCompositionSession<?, ?, ?> session
    ) {
        session.close();
        return !session.isOpen();
    }

    @Nonnull
    private static CommandTargetHudSnapshot selfTestTargetSnapshot(@Nonnull UUID targetUuid) {
        return new CommandTargetHudSnapshot(
                targetUuid, "selftest-target", "selftest-species", "READY",
                CommandTargetHudSnapshot.Vitals.empty(), CommandTargetHudSnapshot.Cooldowns.empty(),
                null, List.of(), List.of(), null, CommandTargetHudSnapshot.Progression.empty(),
                List.of(), "selftest-owner");
    }

    @Nonnull
    private static CommandHotswapHudSnapshot selfTestHotswapSnapshot() {
        CommandHotswapHudSnapshot.Slot slot = new CommandHotswapHudSnapshot.Slot(
                true, "Q", "selftest/icon.png", "Q");
        return new CommandHotswapHudSnapshot(slot, slot, slot, slot, slot,
                CommandHotswapHudSnapshot.GroupStatus.hidden());
    }

    /** Detached result from the target and hotswap composition smoke test. */
    public record CommandHudSelfTestResult(
            boolean targetRendererCreated,
            boolean targetContributionReady,
            boolean targetFocusedRefresh,
            boolean targetSessionClosed,
            boolean hotswapRendererCreated,
            boolean hotswapContributionReady,
            boolean hotswapFocusedRefresh,
            boolean hotswapSessionClosed
    ) {
        @Nonnull
        static CommandHudSelfTestResult unavailable() {
            return new CommandHudSelfTestResult(false, false, false, true,
                    false, false, false, true);
        }
    }

    private record SurfaceSelfTestResult(
            boolean rendererCreated,
            boolean contributionReady,
            boolean focusedRefresh,
            boolean sessionClosed
    ) {
        @Nonnull
        static SurfaceSelfTestResult failed() {
            return new SurfaceSelfTestResult(false, false, false, true);
        }
    }
}

package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightTrailSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Starts and cancels interaction-authored trails for avatar-flight movement cues. */
public final class AvatarFlightTrailService {
    private static final InteractionType TRAIL_INTERACTION_TYPE = InteractionType.EntityStatEffect;
    private static final int PENDING_CHAIN_ID = 1;

    public void tick(@Nonnull AvatarFlightComponent flight,
                     @Nonnull AvatarFlightController.Output output,
                     @Nonnull TwAvatarFlightConfig config,
                     @Nonnull Ref<EntityStore> ref,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        AvatarFlightTrailSettings settings = config.getTrails();
        InteractionManager manager = resolveManager(ref, commandBuffer);
        if (!settings.isEnabled() || manager == null) {
            stopFastGlideTrail(flight, manager, settings.getFastGlideRootInteraction());
            return;
        }

        if (output.launchApplied()) {
            start(settings.getLaunchRootInteraction(), ref, commandBuffer, manager);
        }
        if (output.jumpApplied()) {
            start(settings.getFlapRootInteraction(), ref, commandBuffer, manager);
        }
        if (output.boostApplied()) {
            start(settings.getBoostRootInteraction(), ref, commandBuffer, manager);
        }

        String fastGlideRootInteraction = settings.getFastGlideRootInteraction();
        boolean running = isFastGlideTrailRunning(flight, manager, fastGlideRootInteraction);
        double horizontalSpeed = AvatarFlightSpeedMetrics.horizontalSpeed(
                output.velocityX(), output.velocityY(), output.velocityZ());
        boolean desired = output.applyVelocity() && AvatarFlightTrailPolicy.shouldRunFastGlideTrail(
                running,
                horizontalSpeed,
                AvatarFlightSpeedMetrics.glideHorizontalCap(config),
                settings
        );
        if (desired && !running) {
            InteractionChain chain = start(
                    fastGlideRootInteraction, ref, commandBuffer, manager);
            flight.setFastGlideTrailChainId(chain == null ? 0 : PENDING_CHAIN_ID);
        } else if (!desired && running) {
            stopFastGlideTrail(flight, manager, fastGlideRootInteraction);
        }
    }

    public static void stopFastGlideTrail(@Nullable AvatarFlightComponent flight,
                                          @Nonnull Ref<EntityStore> ref,
                                          @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (flight == null) return;
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(flight.getConfigId());
        stopFastGlideTrail(
                flight,
                resolveManager(ref, componentAccessor),
                config.getTrails().getFastGlideRootInteraction());
    }

    private static void stopFastGlideTrail(@Nonnull AvatarFlightComponent flight,
                                           @Nullable InteractionManager manager,
                                           @Nonnull String rootInteractionId) {
        int chainId = flight.getFastGlideTrailChainId();
        if (manager != null) {
            InteractionChain chain = chainId < 0 ? manager.getChains().get(chainId) : null;
            if (chain == null) chain = findChain(manager, rootInteractionId);
            if (chain != null) manager.cancelChains(chain);
        }
        flight.setFastGlideTrailChainId(0);
    }

    private static boolean isFastGlideTrailRunning(@Nonnull AvatarFlightComponent flight,
                                                    @Nonnull InteractionManager manager,
                                                    @Nonnull String rootInteractionId) {
        int chainId = flight.getFastGlideTrailChainId();
        if (chainId == 0) {
            InteractionChain chain = findChain(manager, rootInteractionId);
            if (chain == null) return false;
            flight.setFastGlideTrailChainId(chain.getChainId());
            return true;
        }
        if (chainId == PENDING_CHAIN_ID) {
            InteractionChain chain = findChain(manager, rootInteractionId);
            if (chain == null) return true;
            flight.setFastGlideTrailChainId(chain.getChainId());
            return true;
        }
        if (manager.getChains().containsKey(chainId)) return true;
        flight.setFastGlideTrailChainId(0);
        return false;
    }

    @Nullable
    private static InteractionChain findChain(@Nonnull InteractionManager manager,
                                              @Nonnull String rootInteractionId) {
        if (rootInteractionId.isBlank()) return null;
        for (InteractionChain chain : manager.getChains().values()) {
            if (rootInteractionId.equals(chain.getInitialRootInteraction().getId())) {
                return chain;
            }
        }
        return null;
    }

    @Nullable
    private static InteractionChain start(@Nonnull String rootInteractionId,
                                          @Nonnull Ref<EntityStore> ref,
                                          @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                          @Nonnull InteractionManager manager) {
        if (rootInteractionId.isBlank()) return null;
        RootInteraction root = RootInteraction.getAssetMap().getAsset(rootInteractionId);
        if (root == null) return null;
        InteractionContext context = InteractionContext.forInteraction(
                manager, ref, TRAIL_INTERACTION_TYPE, commandBuffer);
        InteractionChain chain = manager.initChain(
                TRAIL_INTERACTION_TYPE, context, root, false);
        // InteractionManager assigns its entity ref only inside its own tick. Queueing avoids
        // tickChain throwing and removing the world when another ECS system starts a trail.
        manager.queueExecuteChain(chain);
        return chain;
    }

    @Nullable
    private static InteractionManager resolveManager(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        InteractionModule module = InteractionModule.get();
        return module == null ? null : componentAccessor.getComponent(
                ref, module.getInteractionManagerComponent());
    }
}

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

    public void tick(@Nonnull AvatarFlightComponent flight,
                     @Nonnull AvatarFlightController.Output output,
                     @Nonnull TwAvatarFlightConfig config,
                     @Nonnull Ref<EntityStore> ref,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        AvatarFlightTrailSettings settings = config.getTrails();
        InteractionManager manager = resolveManager(ref, commandBuffer);
        if (!settings.isEnabled() || manager == null) {
            stopFastGlideTrail(flight, manager);
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

        boolean running = isFastGlideTrailRunning(flight, manager);
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
                    settings.getFastGlideRootInteraction(), ref, commandBuffer, manager);
            flight.setFastGlideTrailChainId(chain == null ? 0 : chain.getChainId());
        } else if (!desired && running) {
            stopFastGlideTrail(flight, manager);
        }
    }

    public static void stopFastGlideTrail(@Nullable AvatarFlightComponent flight,
                                          @Nonnull Ref<EntityStore> ref,
                                          @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (flight == null) return;
        stopFastGlideTrail(flight, resolveManager(ref, componentAccessor));
    }

    private static void stopFastGlideTrail(@Nonnull AvatarFlightComponent flight,
                                           @Nullable InteractionManager manager) {
        int chainId = flight.getFastGlideTrailChainId();
        if (chainId == 0) return;
        if (manager != null) {
            InteractionChain chain = manager.getChains().get(chainId);
            if (chain != null) manager.cancelChains(chain);
        }
        flight.setFastGlideTrailChainId(0);
    }

    private static boolean isFastGlideTrailRunning(@Nonnull AvatarFlightComponent flight,
                                                    @Nonnull InteractionManager manager) {
        int chainId = flight.getFastGlideTrailChainId();
        if (chainId == 0) return false;
        if (manager.getChains().containsKey(chainId)) return true;
        flight.setFastGlideTrailChainId(0);
        return false;
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
        manager.executeChain(ref, commandBuffer, chain);
        return chain.getChainId() == 0 ? null : chain;
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

package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.effects.TameworkEntityEffectService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Owns the intentionally process-local begin/cancel/complete channel handoff. */
final class SpawnerCaptureChannelService {
    private static final long COMPLETION_RESTART_GUARD_MS = 750L;
    private final ConcurrentHashMap<UUID, CaptureAttemptHandle> attempts =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> completionRestartGuards =
            new ConcurrentHashMap<>();

    boolean start(
            Player player,
            Ref<EntityStore> targetRef,
            ItemFeatureConfig config,
            CaptureAttemptHandle attempt,
            String beamParticleSystem,
            double beamNativeLength,
            double beamNativeDurationSeconds,
            boolean scaleBeamToTarget,
            boolean beamFromTarget,
            double channelDurationSeconds,
            CaptureHomingProjectileSettings homingProjectileSettings
    ) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null
                || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        UUID actorUuid = player == null
                ? null
                : componentUuid(store, player.getReference());
        UUID targetUuid = componentUuid(store, targetRef);
        if (world == null || actorUuid == null || targetUuid == null
                || config == null || attempt == null
                || completionRestartGuardActive(actorUuid)
                || !CaptureChannelVfxSystem.start(
                        actorUuid,
                        targetUuid,
                        world,
                        beamParticleSystem,
                        beamNativeLength,
                        beamNativeDurationSeconds,
                        scaleBeamToTarget,
                        beamFromTarget,
                        channelDurationSeconds,
                        config.getCaptureMaxDistance(),
                        config.getCaptureChannelAuraEffectId(),
                        homingProjectileSettings
                )) {
            return false;
        }
        TameworkEntityEffectService.applyEffect(
                targetRef,
                config.getCaptureChannelAuraEffectId(),
                store
        );
        new SpawnerEffectService().playCaptureChannelSound(world, targetRef, config);
        attempts.put(actorUuid, attempt);
        return true;
    }

    void end(
            Player player,
            Ref<EntityStore> targetRef,
            ItemFeatureConfig config
    ) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null
                || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        UUID actorUuid = player == null
                ? null
                : componentUuid(store, player.getReference());
        if (actorUuid != null) {
            attempts.remove(actorUuid);
        }
        if (world == null || store == null || config == null) {
            return;
        }
        Ref<EntityStore> locked = actorUuid == null
                ? null
                : CaptureChannelVfxSystem.stop(actorUuid, world);
        TameworkEntityEffectService.removeEffect(
                targetRef == null ? locked : targetRef,
                config.getCaptureChannelAuraEffectId(),
                store
        );
    }

    @Nullable
    CaptureAttemptHandle take(Player player) {
        UUID actorUuid = actorUuid(player);
        if (actorUuid == null) {
            return null;
        }
        CaptureAttemptHandle attempt = attempts.remove(actorUuid);
        if (attempt != null) {
            completionRestartGuards.put(
                    actorUuid,
                    System.currentTimeMillis() + COMPLETION_RESTART_GUARD_MS
            );
        }
        return attempt;
    }

    private boolean completionRestartGuardActive(UUID actorUuid) {
        Long untilMs = completionRestartGuards.get(actorUuid);
        if (untilMs == null) {
            return false;
        }
        if (System.currentTimeMillis() < untilMs) {
            return true;
        }
        completionRestartGuards.remove(actorUuid, untilMs);
        return false;
    }

    @Nullable
    private UUID actorUuid(Player player) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null
                || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        return player == null
                ? null
                : componentUuid(store, player.getReference());
    }

    @Nullable
    private UUID componentUuid(
            Store<EntityStore> store,
            Ref<EntityStore> reference
    ) {
        if (store == null || reference == null || !reference.isValid()
                || UUIDComponent.getComponentType() == null) {
            return null;
        }
        UUIDComponent identity = store.getComponent(
                reference, UUIDComponent.getComponentType()
        );
        return identity == null ? null : identity.getUuid();
    }
}

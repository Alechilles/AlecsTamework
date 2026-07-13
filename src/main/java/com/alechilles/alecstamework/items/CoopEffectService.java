package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Emits configured coop transition particle/sound effects without owning lifecycle authority. */
final class CoopEffectService {
    /** Resolves the current NPC position and configured coop effect on the owning world thread. */
    void playTransitionEffects(@Nullable Store<EntityStore> store,
                               @Nullable Ref<EntityStore> reference,
                               @Nullable String coopId) {
        if (store == null || reference == null || !reference.isValid()
                || coopId == null || coopId.isBlank()) {
            return;
        }
        store.assertThread();
        try {
            World world = store.getExternalData() == null
                    ? null : store.getExternalData().getWorld();
            TransformComponent transform = store.getComponent(
                    reference, TransformComponent.getComponentType());
            TwCoopConfig config = TwCoopConfig.resolveForCoop(coopId);
            if (transform == null || transform.getPosition() == null || config == null) {
                return;
            }
            playIntakeEffects(
                    world, new Vector3d(transform.getPosition()), config.getCapturePolicy());
        } catch (RuntimeException ignored) {
            // Optional presentation must never roll back or block a committed coop transition.
        }
    }

    void playIntakeEffects(@Nullable World world,
                           @Nullable Vector3d position,
                           @Nullable TwCoopConfig.CapturePolicySettings policy) {
        if (world == null || position == null || policy == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore() != null
                ? world.getEntityStore().getStore() : null;
        if (store == null) {
            return;
        }
        String particleSystem = policy.getParticleSystem();
        if (particleSystem != null && !particleSystem.isBlank()) {
            ParticleUtil.spawnParticleEffect(particleSystem, position, store);
        }
        String soundEvent = policy.getSoundEvent();
        if (soundEvent == null || soundEvent.isBlank()) {
            return;
        }
        int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
        if (soundEventIndex > 0) {
            SoundUtil.playSoundEvent3d(soundEventIndex, SoundCategory.SFX, position, store);
        }
    }
}

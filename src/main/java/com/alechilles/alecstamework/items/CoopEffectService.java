package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Emits configured coop intake particle/sound effects.
 */
final class CoopEffectService {
    void playIntakeEffects(@Nullable World world,
                           @Nullable Vector3d position,
                           @Nullable TwCoopConfig.CapturePolicySettings policy) {
        if (world == null || position == null || policy == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
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

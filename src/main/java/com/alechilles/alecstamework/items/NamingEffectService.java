package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Emits naming success visual/audio effects.
 */
public final class NamingEffectService {

    public void playSuccessEffects(World world,
                                   Ref<EntityStore> targetRef,
                                   String particleSystem,
                                   String soundEvent) {
        if (world == null || targetRef == null || !targetRef.isValid()) {
            return;
        }
        if ((particleSystem == null || particleSystem.isBlank())
                && (soundEvent == null || soundEvent.isBlank())) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        if (particleSystem != null && !particleSystem.isBlank()) {
            ParticleUtil.spawnParticleEffect(particleSystem, position, store);
        }
        if (soundEvent == null || soundEvent.isBlank()) {
            return;
        }
        int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
        if (soundEventIndex > 0) {
            SoundUtil.playSoundEvent3d(soundEventIndex, SoundCategory.SFX, position, store);
        }
    }
}


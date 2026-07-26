package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Emits spawner particle and sound effects for capture/spawn events.
 */
public final class SpawnerEffectService {

    public void playSpawnEffects(World world, Ref<EntityStore> targetRef, ItemFeatureConfig config) {
        if (config == null) {
            return;
        }
        playEffects(world, targetRef, config.getSpawnParticleSystem(), config.getSpawnSoundEvent());
    }

    public void playCaptureEffects(World world, Ref<EntityStore> targetRef, ItemFeatureConfig config) {
        if (config == null) {
            return;
        }
        playEffects(world, targetRef, config.getCaptureParticleSystem(), config.getCaptureSoundEvent());
    }

    /** Emits the configured immediate sound feedback for a capture channel beginning. */
    public void playCaptureChannelSound(World world, Ref<EntityStore> targetRef, ItemFeatureConfig config) {
        if (config == null) {
            return;
        }
        playEffects(world, targetRef, null, config.getCaptureChannelSoundEvent());
    }

    /** Emits one immutable effect plan after its canonical workflow publishes. */
    public boolean playPublishedEffect(
            World world,
            SpawnerPublishedEffect effect
    ) {
        if (world == null || effect == null) {
            return false;
        }
        return playPublishedEffects(
                world,
                new Vector3d(effect.x(), effect.y(), effect.z()),
                effect.particleSystem(),
                effect.soundEvent()
        );
    }

    private boolean playPublishedEffects(
            World world,
            Vector3d position,
            String particleSystem,
            String soundEvent
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        boolean invoked = false;
        if (particleSystem != null && !particleSystem.isBlank()) {
            if (ParticleSystem.getAssetMap() == null
                    || ParticleSystem.getAssetMap().getAsset(
                    particleSystem) == null) return false;
            ParticleUtil.spawnParticleEffect(particleSystem, position, store);
            invoked = true;
        }
        if (soundEvent == null || soundEvent.isBlank()) {
            return invoked;
        }
        if (SoundEvent.getAssetMap() == null) return false;
        int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
        if (soundEventIndex <= 0) return false;
        SoundUtil.playSoundEvent3d(
                soundEventIndex, SoundCategory.SFX, position, store);
        return true;
    }

    /** Emits the configured non-durable feedback for one terminal failed roll. */
    public void playCaptureFailureEffects(
            World world,
            Ref<EntityStore> targetRef,
            ItemFeatureConfig.CaptureItemMechanics mechanics) {
        if (mechanics == null) {
            return;
        }
        playEffects(
                world,
                targetRef,
                mechanics.failureParticleSystem(),
                mechanics.failureSoundEvent()
        );
    }

    private void playEffects(World world, Ref<EntityStore> targetRef, String particleSystem, String soundEvent) {
        if (world == null || targetRef == null || !targetRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return;
        }
        playEffects(
                world,
                new Vector3d(transform.getPosition()),
                particleSystem,
                soundEvent
        );
    }

    private void playEffects(
            World world,
            Vector3d position,
            String particleSystem,
            String soundEvent
    ) {
        Store<EntityStore> store = world.getEntityStore().getStore();
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

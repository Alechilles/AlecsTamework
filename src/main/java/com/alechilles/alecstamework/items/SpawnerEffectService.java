package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;

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

    public void playEffects(World world, Ref<EntityStore> targetRef, String particleSystem, String soundEvent) {
        if (world == null || targetRef == null || !targetRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        List<Ref<EntityStore>> playerRefs = resolvePlayerRefs(world);
        if (particleSystem != null && !particleSystem.isBlank() && !playerRefs.isEmpty()) {
            ParticleUtil.spawnParticleEffect(particleSystem, position, targetRef, playerRefs, store);
        }
        if (soundEvent == null || soundEvent.isBlank()) {
            return;
        }
        int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEvent);
        if (soundEventIndex > 0) {
            SoundUtil.playSoundEvent3d(soundEventIndex, SoundCategory.SFX, position, store);
        }
    }

    private List<Ref<EntityStore>> resolvePlayerRefs(World world) {
        if (world == null) {
            return List.of();
        }
        List<Ref<EntityStore>> refs = new ArrayList<>();
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            if (playerRef == null) {
                continue;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                refs.add(ref);
            }
        }
        return refs;
    }
}


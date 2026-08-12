package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.compat.HytaleParticleAccess;
import com.alechilles.alecstamework.companion.coop.runtime.CoopTransitionEffectSink;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

/** Emits configured coop transition particle/sound effects without owning lifecycle authority. */
public final class CoopEffectService implements CoopTransitionEffectSink {
    @Override
    public void play(
            World world,
            double x,
            double y,
            double z,
            String coopId
    ) {
        if (world == null || coopId == null || coopId.isBlank()
                || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            return;
        }
        try {
            TwCoopConfig config = TwCoopConfig.resolveForCoop(coopId);
            if (config == null) {
                return;
            }
            playIntakeEffects(
                    world,
                    new Vector3d(x, y, z),
                    config.getCapturePolicy()
            );
        } catch (RuntimeException ignored) {
            // Optional presentation must never roll back or block a committed coop transition.
        }
    }

    private void playIntakeEffects(
            World world,
            Vector3d position,
            TwCoopConfig.CapturePolicySettings policy
    ) {
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
            HytaleParticleAccess.spawn(particleSystem, position, store);
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

package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Resolves configured sound-event ids and emits positional launch audio.
 */
final class AvatarFlightLaunchAudioEmitter {
    boolean play(@Nonnull String soundEventId,
                 double x,
                 double y,
                 double z,
                 float volume,
                 float pitch,
                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (soundEventId.isBlank()) return false;
        int soundEventIndex = SoundEvent.getAssetMap().getIndex(soundEventId);
        if (soundEventIndex <= 0) return false;
        SoundUtil.playSoundEvent3d(
                soundEventIndex,
                SoundCategory.SFX,
                x,
                y,
                z,
                volume,
                pitch,
                componentAccessor
        );
        return true;
    }
}

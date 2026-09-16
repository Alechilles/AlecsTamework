package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Shared positional playback boundary for avatar-flight audio cues. */
final class AvatarFlightAudioPlayback {
    private AvatarFlightAudioPlayback() {
    }

    static void play(@Nonnull PlaybackSink playbackSink,
                     @Nonnull String soundEventId,
                     @Nonnull Vector3d position,
                     float volume,
                     float pitch,
                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (soundEventId.isBlank()) {
            return;
        }
        playbackSink.play(
                soundEventId,
                position.x,
                position.y,
                position.z,
                volume,
                pitch,
                componentAccessor
        );
    }

    @FunctionalInterface
    interface PlaybackSink {
        boolean play(@Nonnull String soundEventId,
                     double x,
                     double y,
                     double z,
                     float volume,
                     float pitch,
                     @Nonnull ComponentAccessor<EntityStore> componentAccessor);
    }
}

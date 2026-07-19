package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightAudioSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Emits one-shot positional audio after accepted avatar-flight maneuvers. */
final class AvatarFlightAbilityAudioService {
    private final PlaybackSink playbackSink;

    AvatarFlightAbilityAudioService() {
        this(new AvatarFlightLaunchAudioEmitter()::play);
    }

    AvatarFlightAbilityAudioService(@Nonnull PlaybackSink playbackSink) {
        this.playbackSink = playbackSink;
    }

    void emitApplied(@Nonnull AvatarFlightController.Output output,
                     @Nonnull TwAvatarFlightConfig config,
                     @Nullable TransformComponent transform,
                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        AvatarFlightAudioSettings audio = config.getAudio();
        if (!audio.isEnabled() || transform == null || transform.getPosition() == null) return;

        Vector3d position = transform.getPosition();
        if (output.jumpApplied()) {
            play(audio.getUpwardFlapSoundEvent(), position, componentAccessor);
        }
        if (output.boostApplied()) {
            play(audio.getForwardBoostSoundEvent(), position, componentAccessor);
        }
        if (output.airbrakeApplied()) {
            play(audio.getAirbrakeSoundEvent(), position, componentAccessor);
        }
    }

    private void play(@Nonnull String soundEventId,
                      @Nonnull Vector3d position,
                      @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        playbackSink.play(
                soundEventId,
                position.x,
                position.y,
                position.z,
                1.0F,
                1.0F,
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

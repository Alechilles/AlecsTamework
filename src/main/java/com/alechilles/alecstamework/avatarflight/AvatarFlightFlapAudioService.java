package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightAudioSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Schedules non-overlapping one-shot wing sounds for sustained avatar-flight states. */
final class AvatarFlightFlapAudioService {
    private static final String IDLE_MODE = "Idle";
    private static final String FLIGHT_MODE = "Flight";

    private final PlaybackSink playbackSink;

    AvatarFlightFlapAudioService() {
        this(new AvatarFlightLaunchAudioEmitter()::play);
    }

    AvatarFlightFlapAudioService(@Nonnull PlaybackSink playbackSink) {
        this.playbackSink = playbackSink;
    }

    void tick(@Nonnull AvatarFlightComponent flight,
              @Nonnull AvatarFlightController.Output output,
              @Nonnull TwAvatarFlightConfig config,
              @Nullable TransformComponent transform,
              long now,
              @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        AvatarFlightAudioSettings audio = config.getAudio();
        if (!audio.isEnabled() || !output.applyVelocity() || output.fastFlight()
                || transform == null || transform.getPosition() == null) {
            reset(flight);
            return;
        }

        boolean idle = output.horizontalIdle();
        String mode = idle ? IDLE_MODE : FLIGHT_MODE;
        String soundEventId = idle
                ? audio.getIdleFlightFlapSoundEvent()
                : audio.getFlightFlapSoundEvent();
        long intervalMs = idle
                ? audio.getIdleFlightFlapIntervalMs()
                : audio.getFlightFlapIntervalMs();
        if (soundEventId.isBlank() || intervalMs <= 0L) {
            reset(flight);
            return;
        }

        if (!mode.equals(flight.getFlightFlapAudioMode())) {
            flight.setFlightFlapAudioMode(mode);
            flight.setNextFlightFlapAudioAtMs(now);
        }
        if (now < flight.getNextFlightFlapAudioAtMs()) return;

        Vector3d position = transform.getPosition();
        playbackSink.play(
                soundEventId,
                position.x,
                position.y,
                position.z,
                1.0F,
                1.0F,
                componentAccessor
        );
        // Schedule from the current tick so delayed ticks never cause catch-up bursts.
        flight.setNextFlightFlapAudioAtMs(now + intervalMs);
    }

    private static void reset(@Nonnull AvatarFlightComponent flight) {
        flight.setFlightFlapAudioMode("");
        flight.setNextFlightFlapAudioAtMs(0L);
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

package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightAudioSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Converts launch charge/release state into bounded positional sound emissions.
 */
public final class AvatarFlightLaunchAudioService {

    @FunctionalInterface
    interface PlaybackSink {
        boolean play(String soundEventId, double x, double y, double z, float volume, float pitch,
                     ComponentAccessor<EntityStore> componentAccessor);
    }

    private final PlaybackSink playbackSink;

    public AvatarFlightLaunchAudioService() {
        this(new AvatarFlightLaunchAudioEmitter()::play);
    }

    AvatarFlightLaunchAudioService(@Nonnull PlaybackSink playbackSink) {
        this.playbackSink = playbackSink;
    }

    public void tick(@Nonnull AvatarFlightComponent flight,
                     @Nullable AvatarFlightInputComponent input,
                     @Nonnull AvatarFlightController.Input controllerInput,
                     @Nonnull AvatarFlightController.Output output,
                     @Nonnull TwAvatarFlightConfig config,
                     @Nullable TransformComponent transform,
                     long nowMs,
                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        AvatarFlightAudioSettings audio = config.getAudio();
        if (!config.getLaunch().isEnabled() || !audio.isEnabled()) {
            flight.clearLaunchAudioState();
            return;
        }

        long releasedHoldMs = controllerInput.launchHoldMs();
        if (releasedHoldMs > 0L) {
            emitRelease(output, config, audio, transform, releasedHoldMs, componentAccessor);
            flight.clearLaunchAudioState();
            return;
        }

        if (input == null || !input.isLaunchCharging()) {
            flight.clearLaunchAudioState();
            return;
        }
        if (!input.isOnGround() || transform == null || transform.getPosition() == null) return;

        long heldMs = Math.max(0L, nowMs - input.getLaunchChargeStartedAtMs());
        double progress = AvatarFlightLaunchVfxMath.chargeProgress(heldMs, config.getLaunch().getMaxChargeMs());
        Vector3d position = transform.getPosition();
        emitReadyCue(flight, audio, progress, position, componentAccessor);
        emitChargePulse(flight, audio, progress, position, nowMs, componentAccessor);
    }

    private void emitReadyCue(@Nonnull AvatarFlightComponent flight,
                              @Nonnull AvatarFlightAudioSettings audio,
                              double progress,
                              @Nonnull Vector3d position,
                              @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (progress < 1.0 || flight.isLaunchFullChargeAudioPlayed()) return;
        play(audio.getLaunchReadySoundEvent(), position, 1.0f, 1.0f, componentAccessor);
        flight.setLaunchFullChargeAudioPlayed(true);
    }

    private void emitChargePulse(@Nonnull AvatarFlightComponent flight,
                                 @Nonnull AvatarFlightAudioSettings audio,
                                 double progress,
                                 @Nonnull Vector3d position,
                                 long nowMs,
                                 @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (!isPulseDue(flight, nowMs)) return;
        play(
                audio.getLaunchChargeSoundEvent(),
                position,
                AvatarFlightLaunchAudioMath.pulseVolume(audio, progress),
                AvatarFlightLaunchAudioMath.pulsePitch(audio, progress),
                componentAccessor
        );
        flight.setNextLaunchChargeAudioAtMs(
                nowMs + AvatarFlightLaunchAudioMath.pulseIntervalMs(audio, progress)
        );
    }

    private void emitRelease(@Nonnull AvatarFlightController.Output output,
                             @Nonnull TwAvatarFlightConfig config,
                             @Nonnull AvatarFlightAudioSettings audio,
                             @Nullable TransformComponent transform,
                             long holdMs,
                             @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (transform == null || transform.getPosition() == null) return;
        Vector3d position = transform.getPosition();
        if (!output.launchApplied()) {
            play(audio.getLaunchCancelSoundEvent(), position, 1.0f, 1.0f, componentAccessor);
            return;
        }
        AvatarFlightLaunchVfxMath.ReleaseTier tier =
                AvatarFlightLaunchVfxMath.releaseTier(config.getLaunch(), config.getVfx(), holdMs);
        String soundEventId = switch (tier) {
            case PARTIAL -> audio.getLaunchReleasePartialSoundEvent();
            case MID -> audio.getLaunchReleaseMidSoundEvent();
            case FULL -> audio.getLaunchReleaseFullSoundEvent();
        };
        play(soundEventId, position, 1.0f, 1.0f, componentAccessor);
    }

    private void play(@Nonnull String soundEventId,
                      @Nonnull Vector3d position,
                      float volume,
                      float pitch,
                      @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (soundEventId.isBlank()) return;
        playbackSink.play(soundEventId, position.x, position.y, position.z, volume, pitch, componentAccessor);
    }

    private static boolean isPulseDue(@Nonnull AvatarFlightComponent flight, long nowMs) {
        return flight.getNextLaunchChargeAudioAtMs() == 0L
                || nowMs >= flight.getNextLaunchChargeAudioAtMs();
    }
}

package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightAbilityAnimationSettings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Selects the highest-priority one-shot animation for accepted avatar-flight abilities.
 */
final class AvatarFlightAbilityAnimationSelector {
    enum Ability {
        AIRBRAKE,
        UPWARD_BOOST,
        FORWARD_BOOST
    }

    record Cue(@Nonnull Ability ability, @Nonnull String animationId, long durationMs) {
    }

    private AvatarFlightAbilityAnimationSelector() {
    }

    @Nullable
    static Cue select(@Nonnull AvatarFlightController.Output output,
                      @Nonnull AvatarFlightAbilityAnimationSettings settings) {
        if (output.airbrakeApplied()) {
            return new Cue(Ability.AIRBRAKE, settings.getAirbrakeAnimation(), settings.getAirbrakeDurationMs());
        }
        if (output.jumpApplied()) {
            return new Cue(
                    Ability.UPWARD_BOOST,
                    settings.getUpwardBoostAnimation(),
                    settings.getUpwardBoostDurationMs()
            );
        }
        if (output.boostApplied()) {
            return new Cue(
                    Ability.FORWARD_BOOST,
                    settings.getForwardBoostAnimation(),
                    settings.getForwardBoostDurationMs()
            );
        }
        return null;
    }
}

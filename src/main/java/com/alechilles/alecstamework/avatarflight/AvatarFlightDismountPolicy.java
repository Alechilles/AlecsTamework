package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightMountingSettings;
import javax.annotation.Nonnull;

/** Pure grounded back+crouch hold policy for voluntary avatar-flight dismount. */
public final class AvatarFlightDismountPolicy {
    private AvatarFlightDismountPolicy() {
    }

    @Nonnull
    public static Decision evaluate(long nowMs,
                                    long holdStartedAtMs,
                                    boolean grounded,
                                    boolean crouching,
                                    double forwardAxis,
                                    double backwardDeadzone,
                                    @Nonnull AvatarFlightMountingSettings settings) {
        boolean eligibleGround = grounded || !settings.isRequireGroundedDismount();
        boolean requesting = eligibleGround && crouching && forwardAxis < -Math.abs(backwardDeadzone);
        if (!requesting) {
            return new Decision(false, false, 0L);
        }
        long startedAt = holdStartedAtMs == 0L ? nowMs : holdStartedAtMs;
        boolean complete = nowMs - startedAt >= settings.getDismountHoldMs();
        return new Decision(true, complete, startedAt);
    }

    public record Decision(boolean suppressLaunch, boolean complete, long holdStartedAtMs) {
    }
}

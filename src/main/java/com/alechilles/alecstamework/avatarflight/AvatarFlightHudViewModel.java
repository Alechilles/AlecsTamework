package com.alechilles.alecstamework.avatarflight;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable render data for the compact avatar-flight vigour HUD.
 */
public record AvatarFlightHudViewModel(boolean visible,
                                       double speedRatio,
                                       double vigourCharges,
                                       double maxVigourCharges,
                                       boolean dimmed,
                                       @Nonnull String rechargeMode) {
    public static final int MAX_DISPLAY_PIPS = 6;
    private static final double FULL_EPSILON = 0.0001;
    private static final String RECHARGE_MODE_NONE = "NONE";

    public AvatarFlightHudViewModel {
        if (!visible) {
            speedRatio = 0.0;
            vigourCharges = 0.0;
            maxVigourCharges = 0.0;
            dimmed = false;
            rechargeMode = RECHARGE_MODE_NONE;
        } else {
            speedRatio = clamp01(speedRatio);
            maxVigourCharges = clamp(finiteOrZero(maxVigourCharges), 0.0, MAX_DISPLAY_PIPS);
            vigourCharges = clamp(finiteOrZero(vigourCharges), 0.0, maxVigourCharges);
            rechargeMode = normalizeRechargeMode(rechargeMode);
        }
    }

    @Nonnull
    public static AvatarFlightHudViewModel hidden() {
        return new AvatarFlightHudViewModel(false, 0.0, 0.0, 0.0, false, RECHARGE_MODE_NONE);
    }

    @Nonnull
    public static AvatarFlightHudViewModel visible(double speedRatio,
                                                   double charges,
                                                   double maxCharges,
                                                   boolean groundedAtFull,
                                                   @Nullable String rechargeMode) {
        double displayMax = clamp(finiteOrZero(maxCharges), 0.0, MAX_DISPLAY_PIPS);
        double displayCharges = clamp(finiteOrZero(charges), 0.0, displayMax);
        boolean dimmed = groundedAtFull && displayMax > 0.0 && displayCharges >= displayMax - FULL_EPSILON;
        return new AvatarFlightHudViewModel(
                true,
                speedRatio,
                displayCharges,
                displayMax,
                dimmed,
                normalizeRechargeMode(rechargeMode)
        );
    }

    public double pipFill(int index) {
        if (!visible || index < 0 || index >= MAX_DISPLAY_PIPS || index >= maxVigourCharges) {
            return 0.0;
        }
        return clamp(vigourCharges - index, 0.0, Math.min(1.0, maxVigourCharges - index));
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp01(double value) {
        return clamp(finiteOrZero(value), 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Nonnull
    private static String normalizeRechargeMode(@Nullable String rechargeMode) {
        if (rechargeMode == null || rechargeMode.isBlank()) {
            return RECHARGE_MODE_NONE;
        }
        return rechargeMode.trim().toUpperCase(Locale.ROOT);
    }
}

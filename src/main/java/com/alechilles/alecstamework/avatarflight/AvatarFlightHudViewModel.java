package com.alechilles.alecstamework.avatarflight;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable render data for the compact avatar-flight vigour HUD.
 */
public record AvatarFlightHudViewModel(boolean visible,
                                       double speedRatio,
                                       double targetSpeedRatio,
                                       double pitchDegrees,
                                       double vigourCharges,
                                       double maxVigourCharges,
                                       boolean dimmed,
                                       @Nonnull String rechargeMode,
                                       boolean launchChargeVisible,
                                       double launchChargeRatio,
                                       double launchMinChargeRatio) {
    public static final int MAX_DISPLAY_PIPS = 6;
    private static final double FULL_EPSILON = 0.0001;
    private static final String RECHARGE_MODE_NONE = "NONE";

    public AvatarFlightHudViewModel {
        if (!visible) {
            speedRatio = 0.0;
            targetSpeedRatio = 0.0;
            pitchDegrees = 0.0;
            vigourCharges = 0.0;
            maxVigourCharges = 0.0;
            dimmed = false;
            rechargeMode = RECHARGE_MODE_NONE;
            launchChargeVisible = false;
            launchChargeRatio = 0.0;
            launchMinChargeRatio = 0.0;
        } else {
            speedRatio = clamp01(speedRatio);
            targetSpeedRatio = clamp01(targetSpeedRatio);
            pitchDegrees = finiteOrZero(pitchDegrees);
            maxVigourCharges = Math.max(0.0, finiteOrZero(maxVigourCharges));
            vigourCharges = clamp(finiteOrZero(vigourCharges), 0.0, maxVigourCharges);
            rechargeMode = normalizeRechargeMode(rechargeMode);
            launchChargeRatio = clamp01(launchChargeRatio);
            launchMinChargeRatio = clamp01(launchMinChargeRatio);
        }
    }

    @Nonnull
    public static AvatarFlightHudViewModel hidden() {
        return new AvatarFlightHudViewModel(false, 0.0, 0.0, 0.0, 0.0, 0.0, false,
                RECHARGE_MODE_NONE, false, 0.0, 0.0);
    }

    @Nonnull
    public static AvatarFlightHudViewModel visible(double speedRatio,
                                                   double charges,
                                                   double maxCharges,
                                                   boolean groundedAtFull,
                                                   @Nullable String rechargeMode) {
        return visible(speedRatio, speedRatio, 0.0, charges, maxCharges, groundedAtFull, rechargeMode);
    }

    @Nonnull
    public static AvatarFlightHudViewModel visible(double speedRatio,
                                                   double targetSpeedRatio,
                                                   double pitchRadians,
                                                   double charges,
                                                   double maxCharges,
                                                   boolean groundedAtFull,
                                                   @Nullable String rechargeMode) {
        return visible(speedRatio, targetSpeedRatio, pitchRadians, charges, maxCharges,
                groundedAtFull, rechargeMode, false, 0.0, 0.0);
    }

    @Nonnull
    public static AvatarFlightHudViewModel visible(double speedRatio,
                                                   double targetSpeedRatio,
                                                   double pitchRadians,
                                                   double charges,
                                                   double maxCharges,
                                                   boolean groundedAtFull,
                                                   @Nullable String rechargeMode,
                                                   boolean launchChargeVisible,
                                                   double launchChargeRatio,
                                                   double launchMinChargeRatio) {
        double displayMax = Math.max(0.0, finiteOrZero(maxCharges));
        double displayCharges = clamp(finiteOrZero(charges), 0.0, displayMax);
        boolean dimmed = groundedAtFull && displayMax > 0.0 && displayCharges >= displayMax - FULL_EPSILON;
        return new AvatarFlightHudViewModel(
                true,
                speedRatio,
                targetSpeedRatio,
                Math.toDegrees(finiteOrZero(pitchRadians)),
                displayCharges,
                displayMax,
                dimmed,
                normalizeRechargeMode(rechargeMode),
                launchChargeVisible,
                launchChargeRatio,
                launchMinChargeRatio
        );
    }

    @Nonnull
    public String pitchLabel() {
        int rounded = (int) Math.round(pitchDegrees);
        if (rounded > 0) {
            return "+" + rounded + "\u00B0";
        }
        if (rounded < 0) {
            return rounded + "\u00B0";
        }
        return "0\u00B0";
    }

    public double pipFill(int index) {
        if (!visible || index < 0 || index >= MAX_DISPLAY_PIPS || index >= maxVigourCharges) {
            return 0.0;
        }
        double scale = maxVigourCharges > MAX_DISPLAY_PIPS ? MAX_DISPLAY_PIPS / maxVigourCharges : 1.0;
        double displayedMax = maxVigourCharges * scale;
        double displayedCharges = vigourCharges * scale;
        return clamp(displayedCharges - index, 0.0, Math.min(1.0, displayedMax - index));
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

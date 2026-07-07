package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Charged launch input, impulse, and vigour-cost tuning for avatar flight.
 */
public final class AvatarFlightLaunchSettings {
    public static final String INPUT_JUMP_HOLD = "JumpHold";
    public static final String INPUT_CROUCH_HOLD = "CrouchHold";
    public static final String INPUT_REINS_PRIMARY_HOLD = "ReinsPrimaryHold";

    private static final double DEFAULT_MIN_CHARGE_MS = 500.0;
    private static final double DEFAULT_MAX_CHARGE_MS = 3000.0;
    private static final double DEFAULT_CHARGE_EXPONENT = 0.65;
    private static final double DEFAULT_MIN_UP_IMPULSE = 6.0;
    private static final double DEFAULT_MAX_UP_IMPULSE = 18.0;
    private static final double DEFAULT_MIN_FORWARD_IMPULSE = 6.0;
    private static final double DEFAULT_MAX_FORWARD_IMPULSE = 11.0;
    private static final double DEFAULT_PARTIAL_CHARGE_COST = 1.0;
    private static final double DEFAULT_FULL_CHARGE_COST = 2.0;
    private static final double DEFAULT_FULL_CHARGE_COST_THRESHOLD = 0.6;

    public static final BuilderCodec<AvatarFlightLaunchSettings> CODEC = BuilderCodec.builder(
            AvatarFlightLaunchSettings.class,
            AvatarFlightLaunchSettings::new
    )
            .<Boolean>append(new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value == null || value,
                    settings -> settings.enabled)
            .documentation("Whether charged avatar launch is enabled. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("PreferredInput", Codec.STRING),
                    (settings, value) -> settings.preferredInput = inputOrDefault(value, INPUT_CROUCH_HOLD),
                    settings -> settings.preferredInput)
            .documentation("Preferred charged-launch input. Valid values: CrouchHold, JumpHold, ReinsPrimaryHold. Inheritance: missing nested key inherits parent value.")
            .add()
            .<String>append(new KeyedCodec<>("FallbackInput", Codec.STRING),
                    (settings, value) -> settings.fallbackInput = inputOrDefault(value, INPUT_CROUCH_HOLD),
                    settings -> settings.fallbackInput)
            .documentation("Fallback charged-launch input. CrouchHold and JumpHold are built-in packet paths; ReinsPrimaryHold is reserved for future/custom item hold integrations. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MinChargeMs", Codec.DOUBLE),
                    (settings, value) -> settings.minChargeMs = nonNegativeOrDefault(value, DEFAULT_MIN_CHARGE_MS),
                    settings -> settings.minChargeMs)
            .documentation("Minimum charge duration in milliseconds. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxChargeMs", Codec.DOUBLE),
                    (settings, value) -> settings.maxChargeMs = positiveOrDefault(value, DEFAULT_MAX_CHARGE_MS),
                    settings -> settings.maxChargeMs)
            .documentation("Maximum charge duration in milliseconds. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("ChargeExponent", Codec.DOUBLE),
                    (settings, value) -> settings.chargeExponent = positiveOrDefault(value, DEFAULT_CHARGE_EXPONENT),
                    settings -> settings.chargeExponent)
            .documentation("Exponent applied to normalized charge amount. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MinUpImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.minUpImpulse = nonNegativeOrDefault(value, DEFAULT_MIN_UP_IMPULSE),
                    settings -> settings.minUpImpulse)
            .documentation("Vertical launch impulse at minimum charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxUpImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.maxUpImpulse = nonNegativeOrDefault(value, DEFAULT_MAX_UP_IMPULSE),
                    settings -> settings.maxUpImpulse)
            .documentation("Vertical launch impulse at full charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MinForwardImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.minForwardImpulse =
                            nonNegativeOrDefault(value, DEFAULT_MIN_FORWARD_IMPULSE),
                    settings -> settings.minForwardImpulse)
            .documentation("Forward launch impulse at minimum charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("MaxForwardImpulse", Codec.DOUBLE),
                    (settings, value) -> settings.maxForwardImpulse =
                            nonNegativeOrDefault(value, DEFAULT_MAX_FORWARD_IMPULSE),
                    settings -> settings.maxForwardImpulse)
            .documentation("Forward launch impulse at full charge. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("PartialChargeCost", Codec.DOUBLE),
                    (settings, value) -> settings.partialChargeCost =
                            nonNegativeOrDefault(value, DEFAULT_PARTIAL_CHARGE_COST),
                    settings -> settings.partialChargeCost)
            .documentation("Vigour cost for a partial charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FullChargeCost", Codec.DOUBLE),
                    (settings, value) -> settings.fullChargeCost = nonNegativeOrDefault(value, DEFAULT_FULL_CHARGE_COST),
                    settings -> settings.fullChargeCost)
            .documentation("Vigour cost for a full charged launch. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("FullChargeCostThreshold", Codec.DOUBLE),
                    (settings, value) -> settings.fullChargeCostThreshold =
                            clamp01(value, DEFAULT_FULL_CHARGE_COST_THRESHOLD),
                    settings -> settings.fullChargeCostThreshold)
            .documentation("Normalized charge threshold that spends FullChargeCost. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    private boolean enabled = true;
    private String preferredInput = INPUT_CROUCH_HOLD;
    private String fallbackInput = INPUT_CROUCH_HOLD;
    private double minChargeMs = DEFAULT_MIN_CHARGE_MS;
    private double maxChargeMs = DEFAULT_MAX_CHARGE_MS;
    private double chargeExponent = DEFAULT_CHARGE_EXPONENT;
    private double minUpImpulse = DEFAULT_MIN_UP_IMPULSE;
    private double maxUpImpulse = DEFAULT_MAX_UP_IMPULSE;
    private double minForwardImpulse = DEFAULT_MIN_FORWARD_IMPULSE;
    private double maxForwardImpulse = DEFAULT_MAX_FORWARD_IMPULSE;
    private double partialChargeCost = DEFAULT_PARTIAL_CHARGE_COST;
    private double fullChargeCost = DEFAULT_FULL_CHARGE_COST;
    private double fullChargeCostThreshold = DEFAULT_FULL_CHARGE_COST_THRESHOLD;

    public void inheritMissingFrom(@Nonnull AvatarFlightLaunchSettings parent, @Nullable Set<String> keys) {
        if (keys == null) {
            return;
        }
        if (!keys.contains("Enabled")) enabled = parent.enabled;
        if (!keys.contains("PreferredInput")) preferredInput = parent.preferredInput;
        if (!keys.contains("FallbackInput")) fallbackInput = parent.fallbackInput;
        if (!keys.contains("MinChargeMs")) minChargeMs = parent.minChargeMs;
        if (!keys.contains("MaxChargeMs")) maxChargeMs = parent.maxChargeMs;
        if (!keys.contains("ChargeExponent")) chargeExponent = parent.chargeExponent;
        if (!keys.contains("MinUpImpulse")) minUpImpulse = parent.minUpImpulse;
        if (!keys.contains("MaxUpImpulse")) maxUpImpulse = parent.maxUpImpulse;
        if (!keys.contains("MinForwardImpulse")) minForwardImpulse = parent.minForwardImpulse;
        if (!keys.contains("MaxForwardImpulse")) maxForwardImpulse = parent.maxForwardImpulse;
        if (!keys.contains("PartialChargeCost")) partialChargeCost = parent.partialChargeCost;
        if (!keys.contains("FullChargeCost")) fullChargeCost = parent.fullChargeCost;
        if (!keys.contains("FullChargeCostThreshold")) fullChargeCostThreshold = parent.fullChargeCostThreshold;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getPreferredInput() {
        return inputOrDefault(preferredInput, INPUT_CROUCH_HOLD);
    }

    public String getFallbackInput() {
        return inputOrDefault(fallbackInput, INPUT_CROUCH_HOLD);
    }

    public long getMinChargeMs() {
        return Math.round(nonNegativeOrDefault(minChargeMs, DEFAULT_MIN_CHARGE_MS));
    }

    public long getMaxChargeMs() {
        return Math.max(getMinChargeMs(), Math.round(positiveOrDefault(maxChargeMs, DEFAULT_MAX_CHARGE_MS)));
    }

    public double getChargeExponent() {
        return positiveOrDefault(chargeExponent, DEFAULT_CHARGE_EXPONENT);
    }

    public double getMinUpImpulse() {
        return nonNegativeOrDefault(minUpImpulse, DEFAULT_MIN_UP_IMPULSE);
    }

    public double getMaxUpImpulse() {
        return Math.max(getMinUpImpulse(), nonNegativeOrDefault(maxUpImpulse, DEFAULT_MAX_UP_IMPULSE));
    }

    public double getMinForwardImpulse() {
        return nonNegativeOrDefault(minForwardImpulse, DEFAULT_MIN_FORWARD_IMPULSE);
    }

    public double getMaxForwardImpulse() {
        return Math.max(getMinForwardImpulse(), nonNegativeOrDefault(maxForwardImpulse, DEFAULT_MAX_FORWARD_IMPULSE));
    }

    public double getPartialChargeCost() {
        return nonNegativeOrDefault(partialChargeCost, DEFAULT_PARTIAL_CHARGE_COST);
    }

    public double getFullChargeCost() {
        return nonNegativeOrDefault(fullChargeCost, DEFAULT_FULL_CHARGE_COST);
    }

    public double getFullChargeCostThreshold() {
        return clamp01(fullChargeCostThreshold, DEFAULT_FULL_CHARGE_COST_THRESHOLD);
    }

    private static String inputOrDefault(@Nullable String value, @Nonnull String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim();
        if (INPUT_CROUCH_HOLD.equals(normalized)
                || INPUT_JUMP_HOLD.equals(normalized)
                || INPUT_REINS_PRIMARY_HOLD.equals(normalized)) {
            return normalized;
        }
        return fallback;
    }

    private static double positiveOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegativeOrDefault(@Nullable Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    private static double clamp01(@Nullable Double value, double fallback) {
        double resolved = value != null && Double.isFinite(value) ? value : fallback;
        return Math.max(0.0, Math.min(1.0, resolved));
    }
}

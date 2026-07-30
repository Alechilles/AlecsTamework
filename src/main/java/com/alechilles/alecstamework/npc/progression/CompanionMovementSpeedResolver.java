package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Purely resolves the configured, attachment, and progression speed multipliers for one companion.
 */
public final class CompanionMovementSpeedResolver {
    private static final double MIN_SUPPORTED_MULTIPLIER = 0.50;
    private static final double MAX_SUPPORTED_MULTIPLIER = 2.00;
    private static final int STEP_HUNDREDTHS = 5;

    /**
     * Resolves the effective speed multiplier without inspecting or mutating game state.
     *
     * @param config selected role movement settings, or {@code null} for neutral settings
     * @param effectiveAttachments current attachment selections by slot, or {@code null}
     * @param progressionMultiplier externally resolved progression multiplier
     * @return raw, clamped, and quantized multipliers with the selected config ID when available
     */
    @Nonnull
    public Result resolve(@Nullable TwCompanionMovementConfig.ResolvedMovement config,
                          @Nullable Map<String, String> effectiveAttachments,
                          double progressionMultiplier) {
        TwCompanionMovementConfig.ResolvedMovement movement = config == null
                ? new TwCompanionMovementConfig.ResolvedMovement(null, 1.0, 0.50, 2.00, List.of())
                : config;
        double multiplier = neutralIfInvalid(movement.baseMoveSpeedMultiplier());
        for (TwCompanionMovementConfig.AttachmentModifier modifier : movement.attachmentModifiers()) {
            if (matches(modifier, effectiveAttachments)) {
                multiplier = multiplyByFactor(multiplier, modifier.multiplier());
            }
        }
        multiplier = multiplyByFactor(multiplier, progressionMultiplier);
        double min = normalizedBound(movement.minMoveSpeedMultiplier(), MIN_SUPPORTED_MULTIPLIER);
        double max = normalizedBound(movement.maxMoveSpeedMultiplier(), MAX_SUPPORTED_MULTIPLIER);
        if (min > max) {
            double temporary = min;
            min = max;
            max = temporary;
        }
        double clamped = Math.max(min, Math.min(max, multiplier));
        return new Result(movement.configId(), multiplier, clamped, quantize(clamped));
    }

    private static boolean matches(@Nullable TwCompanionMovementConfig.AttachmentModifier modifier,
                                   @Nullable Map<String, String> attachments) {
        if (modifier == null || attachments == null || attachments.isEmpty()) {
            return false;
        }
        String expectedSlot = normalize(modifier.slot());
        if (expectedSlot.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> attachment : attachments.entrySet()) {
            if (!expectedSlot.equals(normalize(attachment.getKey()))) {
                continue;
            }
            String selectedValue = normalize(attachment.getValue());
            for (String allowedValue : modifier.values()) {
                if (selectedValue.equals(normalize(allowedValue))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double normalizedBound(double value, double fallback) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return fallback;
        }
        return Math.max(MIN_SUPPORTED_MULTIPLIER, Math.min(MAX_SUPPORTED_MULTIPLIER, value));
    }

    private static double neutralIfInvalid(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private static double multiplyByFactor(double multiplier, double factor) {
        return neutralIfInvalid(multiplier * neutralIfInvalid(factor));
    }

    private static double quantize(double value) {
        int hundredths = BigDecimal.valueOf(value)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        int snappedHundredths = Math.round(hundredths / (float) STEP_HUNDREDTHS) * STEP_HUNDREDTHS;
        return snappedHundredths / 100.0;
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Resolved values suitable for effect selection and stable runtime fingerprints. */
    public record Result(@Nullable String configId,
                         double rawMultiplier,
                         double clampedMultiplier,
                         double quantizedMultiplier) {
    }
}

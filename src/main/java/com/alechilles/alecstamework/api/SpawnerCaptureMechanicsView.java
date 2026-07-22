package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable API 0.9 view of item-side spawner capture mechanics. */
public record SpawnerCaptureMechanicsView(@Nonnull String configId,
                                         long configRevision,
                                         @Nonnull String sourceItemId,
                                         @Nonnull CaptureChanceMode chanceMode,
                                         int power,
                                         double baseChance,
                                         double chancePerPower,
                                         double minimumChance,
                                         double maximumChance,
                                         long failureCooldownMs,
                                         @Nullable String failureParticleSystem,
                                         @Nullable String failureSoundEvent,
                                         @Nonnull CaptureSourceConsumption sourceConsumption,
                                         @Nonnull CaptureSuccessDisposition successDisposition,
                                         @Nullable String commandFamilyId,
                                         @Nullable String requiredCommandConfigId,
                                         boolean requireCommandAccessItem) {
    /** Source-compatible constructor for API 0.9 consumers. */
    public SpawnerCaptureMechanicsView(String configId,
                                      long configRevision,
                                      String sourceItemId,
                                      CaptureChanceMode chanceMode,
                                      int power,
                                      double baseChance,
                                      double chancePerPower,
                                      double minimumChance,
                                      double maximumChance,
                                      long failureCooldownMs,
                                      String failureParticleSystem,
                                      String failureSoundEvent) {
        this(configId, configRevision, sourceItemId, chanceMode, power, baseChance,
                chancePerPower, minimumChance, maximumChance, failureCooldownMs,
                failureParticleSystem, failureSoundEvent,
                CaptureSourceConsumption.SUCCESS_ONLY, CaptureSuccessDisposition.CAPTURED_ITEM,
                null, null, false);
    }

    public SpawnerCaptureMechanicsView {
        configId = requireText(configId, "configId");
        sourceItemId = requireText(sourceItemId, "sourceItemId");
        chanceMode = Objects.requireNonNull(chanceMode, "chanceMode");
        sourceConsumption = Objects.requireNonNull(sourceConsumption, "sourceConsumption");
        successDisposition = Objects.requireNonNull(successDisposition, "successDisposition");
        failureParticleSystem = normalizeBlank(failureParticleSystem);
        failureSoundEvent = normalizeBlank(failureSoundEvent);
        commandFamilyId = normalizeBlank(commandFamilyId);
        requiredCommandConfigId = normalizeBlank(requiredCommandConfigId);
        if (configRevision < 0L || power < 0 || failureCooldownMs < 0L) {
            throw new IllegalArgumentException("Capture revision, power, and cooldown cannot be negative.");
        }
        validateProbability("baseChance", baseChance);
        validateProbability("minimumChance", minimumChance);
        validateProbability("maximumChance", maximumChance);
        if (!Double.isFinite(chancePerPower) || chancePerPower < 0.0D) {
            throw new IllegalArgumentException("chancePerPower must be finite and non-negative.");
        }
        if (minimumChance > maximumChance) {
            throw new IllegalArgumentException("minimumChance cannot exceed maximumChance.");
        }
        if (successDisposition == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK
                && commandFamilyId == null) {
            throw new IllegalArgumentException("TameAndCommandLink requires commandFamilyId.");
        }
        if (requireCommandAccessItem && requiredCommandConfigId == null) {
            throw new IllegalArgumentException(
                    "requireCommandAccessItem requires requiredCommandConfigId.");
        }
    }

    private static void validateProbability(String field, double value) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(field + " must be between zero and one.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeBlank(Objects.requireNonNull(value, field));
        if (normalized == null) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

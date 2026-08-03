package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable resolved role-side capture difficulty and custom requirements. */
public record CapturePolicyConfigView(@Nonnull String configId,
                                      long configRevision,
                                      int priority,
                                      @Nonnull Set<String> roleIds,
                                      int minimumPower,
                                      double resistance,
                                      double chanceMultiplier,
                                      double missingHealthBonus,
                                      double tranquilizedBonus,
                                      @Nullable Integer guaranteedAtPower,
                                      @Nonnull List<CaptureRequirementSpec> requirements) {
    public CapturePolicyConfigView {
        configId = Objects.requireNonNull(configId, "configId").trim();
        if (configId.isEmpty()) throw new IllegalArgumentException("configId is required.");
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        if (configRevision < 0L || minimumPower < 0
                || (guaranteedAtPower != null && guaranteedAtPower < 0)) {
            throw new IllegalArgumentException("Capture policy revision and power values cannot be negative.");
        }
        if (!Double.isFinite(resistance) || resistance < 0.0D
                || !Double.isFinite(chanceMultiplier) || chanceMultiplier < 0.0D
                || !Double.isFinite(missingHealthBonus) || missingHealthBonus < 0.0D
                || !Double.isFinite(tranquilizedBonus) || tranquilizedBonus < 0.0D) {
            throw new IllegalArgumentException("Capture policy chance inputs must be finite and non-negative.");
        }
    }
}

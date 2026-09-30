package com.alechilles.alecstamework.companion.index;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What the companion panel shows for a companion that is not loaded (spec 6.6). Raw values
 * and config ids only; text and percentages are formatted per viewer at display time. Built
 * by {@code CompanionSummaries} from live components when a snapshot is taken and on unload.
 * Times named {@code ...Ms} follow the component they came from: breeding cooldowns and the
 * harvest alarm are world time (signed, 0 = unset); {@code observedAtMs} is wall clock.
 */
public record CompanionSummary(
        @Nullable String customName,
        @Nullable String nameKey,
        @Nullable String roleId,
        @Nullable String iconId,
        float healthCurrent,
        float healthMax,
        @Nullable String happinessConfigId,
        double happiness,
        @Nullable String needsConfigId,
        double hunger,
        double thirst,
        boolean breedingEnabled,
        long breedingCooldownUntilMs,
        long breedingCooldownStartedAtMs,
        long breedingCooldownDurationMs,
        long harvestAlarmUntilMs,
        @Nullable String levelingConfigId,
        int level,
        double currentXp,
        double totalXp,
        int talentPointsSpent,
        @Nonnull Map<String, Double> traits,
        @Nullable String lifeStage,
        double lifeStageProgress,
        @Nullable String nextLifeStage,
        long lifeStageRemainingMs,
        long observedAtMs
) {
    public CompanionSummary {
        // Insertion order keeps saved files stable across restarts; Map.copyOf order is per-JVM.
        traits = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(traits, "traits")));
    }

    @Nonnull
    public static final CompanionSummary EMPTY = new CompanionSummary(null, null, null, null,
            0f, 0f, null, 0.0, null, 0.0, 0.0, false, 0L, 0L, 0L, 0L,
            null, 0, 0.0, 0.0, 0, Map.of(), null, 0.0, null, 0L, 0L);
}

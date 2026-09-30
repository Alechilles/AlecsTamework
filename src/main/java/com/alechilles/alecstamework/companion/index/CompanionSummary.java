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
 * The life stage is not stored as presentation: the saved panel advances {@link #progression}
 * at display time, as it did from a decoded checkpoint.
 *
 * <p>A config id is null when the companion had no such component, and
 * {@code breedingPresent} records whether it had a breeding component; the saved panel then
 * keeps its fallback values for that section.</p>
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
        boolean breedingPresent,
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
        long observedAtMs,
        long harvestAlarmStartedAtMs,
        long harvestAlarmDurationMs,
        @Nullable String traitsConfigId,
        @Nullable String talentsConfigId,
        @Nullable Progression progression
) {
    public CompanionSummary {
        // Insertion order keeps saved files stable across restarts; Map.copyOf order is per-JVM.
        Objects.requireNonNull(traits, "traits").forEach((id, value) -> {
            Objects.requireNonNull(id, "trait id");
            Objects.requireNonNull(value, "trait value");
        });
        traits = Collections.unmodifiableMap(new LinkedHashMap<>(traits));
    }

    @Nonnull
    public static final CompanionSummary EMPTY = new CompanionSummary(null, null, null, null,
            0f, 0f, null, 0.0, null, 0.0, 0.0, false, false, 0L, 0L, 0L, 0L,
            null, 0, 0.0, 0.0, 0, Map.of(), 0L, 0L, 0L, null, null, null);

    /**
     * The raw life-stage component values that the saved panel needs to rebuild live countdowns
     * and advance the life stage lazily for an unloaded companion (spec 6.6, phase-3 note).
     * Null on the summary when the companion has no life-stage component. World-time fields
     * ({@code bornAtMs}, {@code adolescentAtMs}, {@code adultAtMs}, {@code lastProgressionWorldMs},
     * {@code lifecycleNowMs}) keep their sign; {@code progressionClockMs} and
     * {@code activeProgressMs} are the owner's progression clock and settled active time.
     */
    public record Progression(
            @Nullable String stage,
            long bornAtMs,
            long adolescentAtMs,
            long adultAtMs,
            boolean growthScalingEnabled,
            double ageProgressMs,
            @Nullable String progressionOwnerId,
            long progressionClockMs,
            boolean progressionInitialized,
            long lastProgressionWorldMs,
            long lifecycleNowMs,
            boolean juvenileClockInitialized,
            boolean progressionPaused,
            long activeProgressMs
    ) {
    }
}

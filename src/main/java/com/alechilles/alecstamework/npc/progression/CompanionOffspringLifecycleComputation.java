package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Computes immutable offspring growth timing and scale boundaries. */
final class CompanionOffspringLifecycleComputation {
    private static final long DEFAULT_GROWTH_SECONDS = TimeUnit.MINUTES.toSeconds(7);
    private static final double DEFAULT_BABY_SCALE_FACTOR = 0.33;
    private static final double DEFAULT_ADOLESCENT_SCALE_FACTOR = 0.66;
    private static final double MIN_SCALE = 0.10;

    private CompanionOffspringLifecycleComputation() {
    }

    static Result compute(long nowMs,
                          double adultScale,
                          @Nullable TwBreedingConfig breedingConfig,
                          @Nullable TwBreedingConfig.RoleFamily family,
                          @Nullable String spawnedRoleId,
                          @Nullable Store<EntityStore> store) {
        TwBreedingConfig.OffspringLifecycleSettings lifecycle = breedingConfig != null
                ? breedingConfig.resolveOffspringLifecycle(spawnedRoleId) : null;
        boolean useFamilyScales = lifecycle != null && lifecycle.isEnabled() && family != null;
        boolean hasAdolescent = useFamilyScales
                && family.getAdolescentRoleId() != null
                && !family.getAdolescentRoleId().isBlank();
        Scales scales = scales(adultScale, lifecycle, family, useFamilyScales);
        long totalGrowthMs = growthDurationMs(
                breedingConfig, lifecycle, family, spawnedRoleId, store
        );
        Durations durations = durations(scales, hasAdolescent, totalGrowthMs);
        long adolescentAtMs = BreedingTimeService.saturatingAdd(nowMs, durations.babyMs());
        long adultAtMs = BreedingTimeService.saturatingAdd(
                adolescentAtMs, durations.adolescentMs()
        );
        return new Result(
                scales.babyStart(), scales.adolescentStart(),
                scales.adolescentSwitch(), scales.adultStart(),
                scales.adultSwitch(), scales.adultFinal(),
                adolescentAtMs, adultAtMs,
                BreedingTimeService.saturatingAdd(adultAtMs, durations.adultMs())
        );
    }

    private static Scales scales(double adultScale,
                                 @Nullable TwBreedingConfig.OffspringLifecycleSettings lifecycle,
                                 @Nullable TwBreedingConfig.RoleFamily family,
                                 boolean useFamilyScales) {
        double adultFinal = clamp(adultScale);
        if (!useFamilyScales) {
            double baby = clamp(adultFinal * DEFAULT_BABY_SCALE_FACTOR);
            double adolescent = clamp(adultFinal * DEFAULT_ADOLESCENT_SCALE_FACTOR);
            return new Scales(
                    baby, adolescent, adolescent, adolescent, adultFinal, adultFinal
            );
        }
        return new Scales(
                clamp(adultFinal * lifecycle.resolveBabyStartScale(family)),
                clamp(adultFinal * lifecycle.resolveAdolescentStartScale(family)),
                clamp(adultFinal * lifecycle.resolveAdolescentSwitchScale(family)),
                clamp(adultFinal * lifecycle.resolveAdultStartScale(family)),
                clamp(adultFinal * lifecycle.resolveAdultSwitchScale(family)),
                adultFinal
        );
    }

    private static Durations durations(Scales scales,
                                       boolean hasAdolescent,
                                       long totalGrowthMs) {
        double babySwitch = hasAdolescent
                ? scales.adolescentSwitch() : scales.adultSwitch();
        double babyDelta = Math.abs(babySwitch - scales.babyStart());
        double adolescentDelta = hasAdolescent
                ? Math.abs(scales.adultSwitch() - scales.adolescentStart()) : 0.0;
        double adultDelta = Math.abs(scales.adultFinal() - scales.adultStart());
        double totalDelta = babyDelta + adolescentDelta + adultDelta;
        if (totalDelta <= 0.000001) {
            babyDelta = 1.0;
            adolescentDelta = 0.0;
            adultDelta = 0.0;
            totalDelta = 1.0;
        }
        long babyMs = Math.max(1L, Math.round(totalGrowthMs * (babyDelta / totalDelta)));
        long adolescentMs = hasAdolescent
                ? Math.max(1L, Math.round(totalGrowthMs * (adolescentDelta / totalDelta))) : 0L;
        long adultMs = Math.max(1L, totalGrowthMs - babyMs - adolescentMs);
        return new Durations(babyMs, adolescentMs, adultMs);
    }

    private static long growthDurationMs(
            @Nullable TwBreedingConfig breedingConfig,
            @Nullable TwBreedingConfig.OffspringLifecycleSettings lifecycle,
            @Nullable TwBreedingConfig.RoleFamily family,
            @Nullable String roleId,
            @Nullable Store<EntityStore> store) {
        long configuredSeconds = DEFAULT_GROWTH_SECONDS;
        if (lifecycle != null && lifecycle.isEnabled()) {
            int resolved = lifecycle.resolveTimeToFullGrownSeconds(family);
            configuredSeconds = resolved > 0 ? resolved : configuredSeconds;
        }
        TwBreedingConfig.TimerBasis timerBasis = breedingConfig != null
                ? breedingConfig.resolveTiming(roleId).getTimerBasis()
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
        long converted = BreedingTimeService.toGameDurationMs(
                configuredSeconds, timerBasis, store
        );
        return converted > 0L
                ? converted : TimeUnit.SECONDS.toMillis(Math.max(1L, configuredSeconds));
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 1.0;
        }
        return Math.max(MIN_SCALE, value);
    }

    record Result(double babyStartScale,
                  double adolescentStartScale,
                  double adolescentSwitchScale,
                  double adultStartScale,
                  double adultSwitchScale,
                  double adultFinalScale,
                  long adolescentAtMs,
                  long adultAtMs,
                  long fullyGrownAtMs) {
    }

    private record Scales(double babyStart,
                          double adolescentStart,
                          double adolescentSwitch,
                          double adultStart,
                          double adultSwitch,
                          double adultFinal) {
    }

    private record Durations(long babyMs, long adolescentMs, long adultMs) {
    }
}

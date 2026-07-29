package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummonedCompanionExperienceSystemTest {
    @Test
    void onlyLiveBondedProjectionsAreEligibleForSummonedXp() {
        TameworkProjectionIdentityComponent bonded =
                TameworkProjectionIdentityComponent.bondedCompanion("profile-a", "lease-a");
        TameworkProjectionIdentityComponent ordinary = new TameworkProjectionIdentityComponent(
                "profile-a", "operation-a", TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                null, null, 0L);

        assertTrue(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, bonded, false));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, ordinary, false));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(true, bonded, true));
        assertFalse(SummonedCompanionExperienceSystem.isEligibleForSummonedXp(false, bonded, false));
    }

    @Test
    void liveBondedProjectionRoutesSummonedAwardWhileInactiveProjectionsResetCadence() throws Exception {
        TameworkProjectionIdentityComponent bonded =
                TameworkProjectionIdentityComponent.bondedCompanion("profile-a", "lease-a");
        TameworkProjectionIdentityComponent ordinary = new TameworkProjectionIdentityComponent(
                "profile-a", "operation-a", TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER,
                null, null, 0L);
        TwLevelingConfig.SummonedXpSourceSettings settings = settings(2.0d, 0.5d, 50.0d);
        AtomicReference<CompanionXpSource> source = new AtomicReference<>();
        AtomicReference<Double> amount = new AtomicReference<>(0.0d);

        TameworkLevelingComponent bondedLeveling = leveling(0.25d, 1_000L);
        SummonedCompanionExperienceSystem.processProjection(
                bondedLeveling, bonded, false, settings, 1_250L, 0.25d,
                (awardSource, awardAmount) -> {
                    source.set(awardSource);
                    amount.set(awardAmount);
                });

        assertEquals(CompanionXpSource.SUMMONED, source.get());
        assertEquals(1.0d, amount.get(), 0.00001d);

        TameworkLevelingComponent ordinaryLeveling = leveling(0.49d, 1_000L);
        amount.set(0.0d);
        SummonedCompanionExperienceSystem.processProjection(
                ordinaryLeveling, ordinary, false, settings, 1_250L, 0.01d,
                (awardSource, awardAmount) -> amount.set(awardAmount));
        SummonedCompanionExperienceSystem.processProjection(
                ordinaryLeveling, bonded, false, settings, 1_500L, 0.25d,
                (awardSource, awardAmount) -> amount.set(awardAmount));

        assertEquals(0.0d, amount.get(), 0.00001d);
        assertEquals(0.25d, ordinaryLeveling.getSummonedActiveSeconds(), 0.00001d);

        TameworkLevelingComponent deadLeveling = leveling(0.49d, 1_000L);
        SummonedCompanionExperienceSystem.processProjection(
                deadLeveling, bonded, true, settings, 1_250L, 0.01d,
                (awardSource, awardAmount) -> amount.set(awardAmount));
        assertEquals(0.0d, deadLeveling.getSummonedActiveSeconds(), 0.00001d);
    }

    private static TameworkLevelingComponent leveling(double activeSeconds, long lastSampleAtMs) {
        return new TameworkLevelingComponent(
                "leveling", 1, 0.0d, 0.0d, 0L, activeSeconds, 0.0d, 0L, lastSampleAtMs);
    }

    private static TwLevelingConfig.SummonedXpSourceSettings settings(double xpPerActiveSecond,
                                                                        double awardIntervalSeconds,
                                                                        double maxXpPerHour) throws Exception {
        TwLevelingConfig.SummonedXpSourceSettings settings = new TwLevelingConfig.SummonedXpSourceSettings();
        setField(settings, "enabled", true);
        setField(settings, "xpPerActiveSecond", xpPerActiveSecond);
        setField(settings, "awardIntervalSeconds", awardIntervalSeconds);
        setField(settings, "maxXpPerHour", maxXpPerHour);
        return settings;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

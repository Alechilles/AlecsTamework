package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Store;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Protects starvation/dehydration damage behavior for needs progression. */
class CompanionNeedsServiceDamageTest {
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final double MAX_HEALTH = 250.0;

    @AfterEach
    void tearDown() {
        CompanionRuntimeClock.resetForTests();
    }

    @Test
    void minOnlyPercentDamageTriggersOnlyWhenNeedIsAtMin() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();

        double noMinDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                10.0,
                10.0,
                ONE_MINUTE_MS,
                MAX_HEALTH
        );
        double hungerMinDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                10.0,
                ONE_MINUTE_MS,
                MAX_HEALTH
        );

        assertEquals(0.0, noMinDamage, 0.000001);
        assertEquals(5.0, hungerMinDamage, 0.000001);
    }

    @Test
    void minOnlyPercentDualMinUsesHigherOnlyByDefault() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();

        double dualMinDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                0.0,
                2L * ONE_MINUTE_MS,
                MAX_HEALTH
        );

        assertEquals(15.0, dualMinDamage, 0.000001);
    }

    @Test
    void minOnlyPercentDualMinSupportsSumBothRule() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();
        TwNeedsConfig.DamageSettings damage = config.getDamage();
        setField(damage, "dualNeedRule", TwNeedsConfig.DualNeedRule.SUM_BOTH);

        double dualMinDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                0.0,
                2L * ONE_MINUTE_MS,
                MAX_HEALTH
        );

        assertEquals(25.0, dualMinDamage, 0.000001);
    }

    @Test
    void minOnlyPercentRequiresValidMaxHealth() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();

        double noMaxDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                10.0,
                ONE_MINUTE_MS,
                0.0
        );
        double nanMaxDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                10.0,
                ONE_MINUTE_MS,
                Double.NaN
        );

        assertEquals(0.0, noMaxDamage, 0.000001);
        assertEquals(0.0, nanMaxDamage, 0.000001);
    }

    @Test
    void minOnlyFlatModelRemainsAvailable() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled(TwNeedsConfig.DamageModel.MIN_ONLY_FLAT);
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();

        double hungerMinDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                10.0,
                ONE_MINUTE_MS,
                MAX_HEALTH
        );

        assertEquals(2.0, hungerMinDamage, 0.000001);
    }

    @Test
    void minOnlyPercentPoolsFractionalDamageForLowMaxHealth() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled(TwNeedsConfig.DamageModel.MIN_ONLY_PERCENT);
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();
        double maxHealth = 20.0;
        double perTickDamage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                10.0,
                ONE_MINUTE_MS,
                maxHealth
        );
        assertEquals(0.4, perTickDamage, 0.000001);

        CompanionNeedsService.NeedsDamagePoolResolution tickOne = CompanionNeedsService.resolveNeedsDamagePooling(perTickDamage, 0.0);
        assertEquals(0.0, tickOne.getDamageToApply(), 0.000001);
        assertEquals(0.4, tickOne.getPendingDamageRemainder(), 0.000001);

        CompanionNeedsService.NeedsDamagePoolResolution tickTwo = CompanionNeedsService.resolveNeedsDamagePooling(
                perTickDamage,
                tickOne.getPendingDamageRemainder()
        );
        assertEquals(0.0, tickTwo.getDamageToApply(), 0.000001);
        assertEquals(0.8, tickTwo.getPendingDamageRemainder(), 0.000001);

        CompanionNeedsService.NeedsDamagePoolResolution tickThree = CompanionNeedsService.resolveNeedsDamagePooling(
                perTickDamage,
                tickTwo.getPendingDamageRemainder()
        );
        assertEquals(1.0, tickThree.getDamageToApply(), 0.000001);
        assertEquals(0.2, tickThree.getPendingDamageRemainder(), 0.000001);
    }

    @Test
    void needsDamagePoolingNormalizesUnsafeCarryValues() {
        CompanionNeedsService.NeedsDamagePoolResolution nanCarry =
                CompanionNeedsService.resolveNeedsDamagePooling(0.25, Double.NaN);
        assertEquals(0.0, nanCarry.getDamageToApply(), 0.000001);
        assertEquals(0.25, nanCarry.getPendingDamageRemainder(), 0.000001);

        CompanionNeedsService.NeedsDamagePoolResolution oversizedCarry =
                CompanionNeedsService.resolveNeedsDamagePooling(0.0, 1.75);
        assertEquals(0.0, oversizedCarry.getDamageToApply(), 0.000001);
        assertEquals(0.75, oversizedCarry.getPendingDamageRemainder(), 0.000001);
    }

    @Test
    void loadedNeedsTickCapsLargeElapsedGaps() {
        assertEquals(0L, CompanionNeedsService.capLoadedTickElapsedMs(-1L));
        assertEquals(10_000L, CompanionNeedsService.capLoadedTickElapsedMs(10_000L));
        assertEquals(30_000L, CompanionNeedsService.capLoadedTickElapsedMs(24L * 60L * 60L * 1000L));
    }

    @Test
    void regenSuppressionBlocksNaturalRegenWithoutAllowedHealBudget() {
        CompanionNeedsService.NaturalRegenSuppressionResolution resolution =
                CompanionNeedsService.resolveNaturalRegenSuppression(
                        true,
                        12.0,
                        10.0,
                        0.0,
                        -1.0
                );

        assertEquals(10.0, resolution.getNextBaselineHealth(), 0.000001);
        assertEquals(0.0, resolution.getNextAllowedExternalHeal(), 0.000001);
        assertEquals(2.0, resolution.getHealthOverflowToRemove(), 0.000001);
    }

    @Test
    void regenSuppressionAllowsExternalHealBudgetButStillBlocksOverflow() {
        CompanionNeedsService.NaturalRegenSuppressionResolution withinBudget =
                CompanionNeedsService.resolveNaturalRegenSuppression(
                        true,
                        14.0,
                        10.0,
                        5.0,
                        -1.0
                );
        assertEquals(14.0, withinBudget.getNextBaselineHealth(), 0.000001);
        assertEquals(1.0, withinBudget.getNextAllowedExternalHeal(), 0.000001);
        assertEquals(0.0, withinBudget.getHealthOverflowToRemove(), 0.000001);

        CompanionNeedsService.NaturalRegenSuppressionResolution overflow =
                CompanionNeedsService.resolveNaturalRegenSuppression(
                        true,
                        16.0,
                        10.0,
                        5.0,
                        -1.0
                );
        assertEquals(15.0, overflow.getNextBaselineHealth(), 0.000001);
        assertEquals(0.0, overflow.getNextAllowedExternalHeal(), 0.000001);
        assertEquals(1.0, overflow.getHealthOverflowToRemove(), 0.000001);
    }

    @Test
    void regenSuppressionTreatsZeroBaselineAsUnsetForLegacyComponents() {
        CompanionNeedsService.NaturalRegenSuppressionResolution resolution =
                CompanionNeedsService.resolveNaturalRegenSuppression(
                        true,
                        50.0,
                        0.0,
                        0.0,
                        -1.0
                );

        assertEquals(50.0, resolution.getNextBaselineHealth(), 0.000001);
        assertEquals(0.0, resolution.getNextAllowedExternalHeal(), 0.000001);
        assertEquals(0.0, resolution.getHealthOverflowToRemove(), 0.000001);
    }

    @Test
    void regenSuppressionUsesManagedHealthAnchorWhenBaselineUnset() {
        CompanionNeedsService.NaturalRegenSuppressionResolution resolution =
                CompanionNeedsService.resolveNaturalRegenSuppression(
                        true,
                        50.0,
                        -1.0,
                        0.0,
                        30.0
                );

        assertEquals(30.0, resolution.getNextBaselineHealth(), 0.000001);
        assertEquals(0.0, resolution.getNextAllowedExternalHeal(), 0.000001);
        assertEquals(20.0, resolution.getHealthOverflowToRemove(), 0.000001);
    }

    @Test
    void frequentSuppressionTickSkippedForHealthyCompanionsWithoutResidualState() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TameworkNeedsComponent component = new TameworkNeedsComponent("needs", 50.0, 50.0, 0.0, 0L, 0L);

        boolean required = CompanionNeedsService.requiresFrequentNaturalRegenSuppressionTick(component, config);

        assertEquals(false, required);
    }

    @Test
    void frequentSuppressionTickRequiredWhileNeedsDamageStateActive() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TameworkNeedsComponent component = new TameworkNeedsComponent("needs", 0.0, 50.0, 0.0, 0L, 0L);

        boolean required = CompanionNeedsService.requiresFrequentNaturalRegenSuppressionTick(component, config);

        assertEquals(true, required);
    }

    @Test
    void disabledRuntimeNeedsGateSuppressesDamageAndFrequentSuppression() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();
        TameworkNeedsComponent component = new TameworkNeedsComponent("needs", 0.0, 0.0, 0.0, 0L, 0L);
        TameworkRuntimeSettings disabledNeedsSettings = TameworkRuntimeSettings.from(settingsWithNeedsEnabled(false));

        double damage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                0.0,
                ONE_MINUTE_MS,
                MAX_HEALTH,
                disabledNeedsSettings
        );
        boolean required = CompanionNeedsService.requiresFrequentNaturalRegenSuppressionTick(
                component,
                config,
                disabledNeedsSettings
        );

        assertEquals(0.0, damage, 0.000001);
        assertEquals(false, required);
    }

    @Test
    void runtimeNeedsGateRequiresEnabledConfigAndEnabledSettings() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TameworkRuntimeSettings enabledSettings = TameworkRuntimeSettings.from(settingsWithNeedsEnabled(true));
        TameworkRuntimeSettings disabledSettings = TameworkRuntimeSettings.from(settingsWithNeedsEnabled(false));

        assertEquals(true, NeedsConfigResolver.isRuntimeEnabled(config, enabledSettings));
        assertEquals(false, NeedsConfigResolver.isRuntimeEnabled(config, disabledSettings));

        setField(config, "enabled", false);

        assertEquals(false, NeedsConfigResolver.isRuntimeEnabled(config, enabledSettings));
    }

    @Test
    void frequentSuppressionTickRequiredForResidualSuppressionCleanup() throws Exception {
        TwNeedsConfig config = createConfigWithDamageEnabled();
        TameworkNeedsComponent component = new TameworkNeedsComponent("needs", 50.0, 50.0, 0.0, 0L, 0L);
        component.setRegenSuppressionBaselineHealth(12.0);

        boolean required = CompanionNeedsService.requiresFrequentNaturalRegenSuppressionTick(component, config);

        assertEquals(true, required);
    }

    @Test
    void hardRegenBlockPushesLastDamageTimeIntoFutureWhileSuppressed() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = CompanionNeedsService.resolveHardRegenBlockLastDamageTime(
                true,
                now,
                now
        );
        assertEquals(Instant.parse("2026-01-01T00:02:00Z"), updated);

        Instant alreadyBlocked = Instant.parse("2026-01-01T00:00:45Z");
        Instant unchanged = CompanionNeedsService.resolveHardRegenBlockLastDamageTime(
                true,
                alreadyBlocked,
                now
        );
        assertEquals(alreadyBlocked, unchanged);
    }

    @Test
    void hardRegenBlockRestoresFutureDamageTimeWhenSuppressionStops() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Instant future = Instant.parse("2026-01-01T00:01:00Z");
        Instant restored = CompanionNeedsService.resolveHardRegenBlockLastDamageTime(
                false,
                future,
                now
        );
        assertEquals(now, restored);

        Instant past = Instant.parse("2025-12-31T23:59:00Z");
        Instant unchangedPast = CompanionNeedsService.resolveHardRegenBlockLastDamageTime(
                false,
                past,
                now
        );
        assertEquals(past, unchangedPast);
    }

    @Test
    void damageDisabledByDefaultKeepsLegacyNoDamageBehavior() throws Exception {
        TwNeedsConfig config = createConfig();
        TwNeedsConfig.ValueSettings values = new TwNeedsConfig.ValueSettings();

        double damage = CompanionNeedsService.resolveNeedsDamageAmount(
                config,
                values,
                0.0,
                0.0,
                ONE_MINUTE_MS,
                MAX_HEALTH
        );

        assertEquals(0.0, damage, 0.000001);
    }

    @Test
    void nonLethalModeCapsDamageToLeaveOneHealthPoint() {
        assertEquals(
                5.0f,
                CompanionNeedsService.resolveAppliedDamageAmountFromHealth(5.0, true, 2.0),
                0.000001
        );
        assertEquals(
                1.0f,
                CompanionNeedsService.resolveAppliedDamageAmountFromHealth(5.0, false, 2.0),
                0.000001
        );
        assertEquals(
                0.0f,
                CompanionNeedsService.resolveAppliedDamageAmountFromHealth(1.0, false, 0.5),
                0.000001
        );
    }

    @Test
    void realTimeBasisUsesSessionRuntimeClock() throws Exception {
        TwNeedsConfig config = createConfig();
        TwNeedsConfig.TimingSettings timing = new TwNeedsConfig.TimingSettings();
        setField(timing, "timerBasis", TwNeedsConfig.TimerBasis.REAL_TIME);
        setField(config, "timing", timing);

        assertEquals(0L, invokeResolveNowMs(config));
        CompanionRuntimeClock.advanceByDeltaSeconds(1.5f);
        assertEquals(1500L, invokeResolveNowMs(config));
    }

    private TwNeedsConfig createConfigWithDamageEnabled() throws Exception {
        return createConfigWithDamageEnabled(TwNeedsConfig.DamageModel.MIN_ONLY_PERCENT);
    }

    private TwNeedsConfig createConfigWithDamageEnabled(TwNeedsConfig.DamageModel model) throws Exception {
        TwNeedsConfig config = createConfig();
        TwNeedsConfig.DamageSettings damage = new TwNeedsConfig.DamageSettings();
        setField(damage, "enabled", true);
        setField(damage, "model", model);
        setField(config, "damage", damage);
        return config;
    }

    private TwNeedsConfig createConfig() throws Exception {
        Constructor<TwNeedsConfig> constructor = TwNeedsConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static long invokeResolveNowMs(TwNeedsConfig config) throws Exception {
        Method method = CompanionNeedsService.class.getDeclaredMethod("resolveNowMs", TwNeedsConfig.class, Store.class);
        method.setAccessible(true);
        return (long) method.invoke(null, config, null);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ResolvedTameworkSettings settingsWithNeedsEnabled(boolean enabled) {
        ResolvedTameworkSettings defaults = TameworkSettingsStore.defaultGlobalSettings();
        return new ResolvedTameworkSettings(
                defaults.populationLimitPerPlayerOwnedTotal(),
                defaults.populationPerPlayerLimitScope(),
                defaults.simpleClaimsEnabled(),
                defaults.simpleClaimsLimitPerClaimChunk(),
                defaults.simpleClaimsLimitPerClaimTotal(),
                defaults.simpleClaimsBreedingRequiresClaim(),
                defaults.simpleClaimsProtectTamedFromNonMembers(),
                defaults.blockOwnerDamage(),
                defaults.blockAllPlayerDamageIfOwned(),
                defaults.invulnerableIfOwned(),
                defaults.captureClearsOwner(),
                defaults.spawnSetsOwner(),
                defaults.captureRequiresOwner(),
                defaults.spawnRequiresOwner(),
                defaults.interactionRequiresOwner(),
                defaults.linkingRequiresOwner(),
                enabled,
                defaults.needsTickPolicyMode(),
                defaults.needsOwnerOfflineGraceHours(),
                defaults.needsOwnerOfflineDecayMultiplier(),
                defaults.needsDamageEnabled(),
                defaults.needsDamageModel(),
                defaults.needsDamageDualNeedRule(),
                defaults.needsStarvationDamagePerMinute(),
                defaults.needsDehydrationDamagePerMinute(),
                defaults.needsDamageLethal(),
                defaults.happinessEnabled(),
                defaults.passiveBreedingEnabled(),
                defaults.breedingRequiresHappiness(),
                defaults.breedingGenderEnabled(),
                defaults.traitsEnabled(),
                defaults.levelingEnabled(),
                defaults.talentsEnabled(),
                defaults.reviveSystemEnabled(),
                defaults.recallTeleportingEnabled(),
                defaults.telemetryEnabled(),
                defaults.telemetryBreadcrumbsEnabled()
        );
    }
}

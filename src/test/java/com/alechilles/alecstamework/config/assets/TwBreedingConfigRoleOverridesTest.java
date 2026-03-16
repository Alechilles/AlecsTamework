package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests per-role override patching across breeding config sections. */
class TwBreedingConfigRoleOverridesTest {

    @Test
    void resolveHappinessUsesPerRoleThresholdWhenPresent() throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        TwBreedingConfig.HappinessSettings base = new TwBreedingConfig.HappinessSettings();
        setField(base, "threshold", 70.0);
        setField(config, "happiness", base);

        TwBreedingConfig.RoleOverrideSettings roleOverride = new TwBreedingConfig.RoleOverrideSettings();
        TwBreedingConfig.HappinessSettingsOverride happinessOverride = new TwBreedingConfig.HappinessSettingsOverride();
        setField(happinessOverride, "threshold", 45.0);
        setField(roleOverride, "happiness", happinessOverride);
        setRoleOverride(config, "Tamed_Rat", roleOverride);

        assertEquals(45.0, config.resolveHappiness("tamed_rat").getThreshold(), 0.0001);
        assertEquals(70.0, config.resolveHappiness("Tamed_Fox").getThreshold(), 0.0001);
    }

    @Test
    void resolvePairingAppliesOnlyProvidedFields() throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        TwBreedingConfig.PairingSettings base = new TwBreedingConfig.PairingSettings();
        setField(base, "breedRadius", 15.0);
        setField(base, "requireWanderMode", false);
        setField(base, "maxNearbySameType", 8);
        setField(config, "pairing", base);

        TwBreedingConfig.RoleOverrideSettings roleOverride = new TwBreedingConfig.RoleOverrideSettings();
        TwBreedingConfig.PairingSettingsOverride pairingOverride = new TwBreedingConfig.PairingSettingsOverride();
        setField(pairingOverride, "maxNearbySameType", 3);
        setField(roleOverride, "pairing", pairingOverride);
        setRoleOverride(config, "Tamed_Hyena", roleOverride);

        TwBreedingConfig.PairingSettings resolved = config.resolvePairing("Tamed_Hyena");
        assertEquals(15.0, resolved.getBreedRadius(), 0.0001);
        assertEquals(false, resolved.isRequireWanderMode());
        assertEquals(3, resolved.getMaxNearbySameType());
    }

    @Test
    void resolveCooldownAndTimingMatchRoleCaseAndNamespaceInsensitive() throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        TwBreedingConfig.CooldownSettings baseCooldowns = new TwBreedingConfig.CooldownSettings();
        setField(baseCooldowns, "baseCooldownSeconds", 1000);
        setField(baseCooldowns, "minDelaySeconds", 10);
        setField(baseCooldowns, "maxDelaySeconds", 20);
        setField(config, "cooldowns", baseCooldowns);
        TwBreedingConfig.TimingSettings baseTiming = new TwBreedingConfig.TimingSettings();
        setField(baseTiming, "timerBasis", TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED);
        setField(config, "timing", baseTiming);

        TwBreedingConfig.RoleOverrideSettings roleOverride = new TwBreedingConfig.RoleOverrideSettings();
        TwBreedingConfig.CooldownSettingsOverride cooldownOverride = new TwBreedingConfig.CooldownSettingsOverride();
        setField(cooldownOverride, "baseCooldownSeconds", 2000);
        setField(roleOverride, "cooldowns", cooldownOverride);
        TwBreedingConfig.TimingSettingsOverride timingOverride = new TwBreedingConfig.TimingSettingsOverride();
        setField(timingOverride, "timerBasis", TwBreedingConfig.TimerBasis.REAL_TIME);
        setField(roleOverride, "timing", timingOverride);
        setRoleOverride(config, "Tamed_Wolf_Black", roleOverride);

        TwBreedingConfig.CooldownSettings resolvedCooldowns = config.resolveCooldowns("mods:tamed_wolf_black");
        assertEquals(2000, resolvedCooldowns.getBaseCooldownSeconds());
        assertEquals(10, resolvedCooldowns.getMinDelaySeconds());
        assertEquals(20, resolvedCooldowns.getMaxDelaySeconds());
        assertEquals(TwBreedingConfig.TimerBasis.REAL_TIME, config.resolveTiming("tamed_wolf_black").getTimerBasis());
    }

    @Test
    void resolveInheritanceSupportsNestedAttachmentPatching() throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        TwBreedingConfig.InheritanceSettings base = new TwBreedingConfig.InheritanceSettings();
        setField(base, "inheritOwner", true);
        setField(base, "inheritTraits", false);
        TwBreedingConfig.AttachmentInheritanceSettings baseAttachment =
                new TwBreedingConfig.AttachmentInheritanceSettings();
        setField(baseAttachment, "parentWeight", 1.0);
        setField(baseAttachment, "randomWeight", 0.25);
        setField(baseAttachment, "mutationChance", 0.05);
        setField(base, "attachmentInheritance", baseAttachment);
        setField(config, "inheritance", base);

        TwBreedingConfig.RoleOverrideSettings roleOverride = new TwBreedingConfig.RoleOverrideSettings();
        TwBreedingConfig.InheritanceSettingsOverride inheritanceOverride =
                new TwBreedingConfig.InheritanceSettingsOverride();
        setField(inheritanceOverride, "inheritTraits", true);
        TwBreedingConfig.AttachmentInheritanceSettingsOverride attachmentOverride =
                new TwBreedingConfig.AttachmentInheritanceSettingsOverride();
        setField(attachmentOverride, "mutationChance", 0.20);
        setField(inheritanceOverride, "attachmentInheritance", attachmentOverride);
        setField(roleOverride, "inheritance", inheritanceOverride);
        setRoleOverride(config, "Tamed_Yeti", roleOverride);

        TwBreedingConfig.InheritanceSettings resolved = config.resolveInheritance("tamed_yeti");
        assertEquals(true, resolved.isInheritOwner());
        assertEquals(true, resolved.isInheritTraits());
        assertEquals(1.0, resolved.getAttachmentInheritance().getParentWeight(), 0.0001);
        assertEquals(0.25, resolved.getAttachmentInheritance().getRandomWeight(), 0.0001);
        assertEquals(0.20, resolved.getAttachmentInheritance().getMutationChance(), 0.0001);
    }

    @Test
    void resolveLifecycleUsesRoleOverrideDefaultGrowthTime() throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        TwBreedingConfig.OffspringLifecycleSettings base = new TwBreedingConfig.OffspringLifecycleSettings();
        setField(base, "defaultTimeToFullGrownSeconds", 7200);
        setField(config, "offspringLifecycle", base);

        TwBreedingConfig.RoleOverrideSettings roleOverride = new TwBreedingConfig.RoleOverrideSettings();
        TwBreedingConfig.OffspringLifecycleSettingsOverride lifecycleOverride =
                new TwBreedingConfig.OffspringLifecycleSettingsOverride();
        setField(lifecycleOverride, "defaultTimeToFullGrownSeconds", 3000);
        setField(roleOverride, "offspringLifecycle", lifecycleOverride);
        setRoleOverride(config, "Tamed_Rat", roleOverride);

        assertEquals(3000, config.resolveOffspringLifecycle("Tamed_Rat").resolveTimeToFullGrownSeconds(null));
        assertEquals(7200, config.resolveOffspringLifecycle("Tamed_Fox").resolveTimeToFullGrownSeconds(null));
    }

    @Test
    void resolveLifecycleFallsBackToMatchingAdultFamilyOverrideForBabyRole() throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        TwBreedingConfig.OffspringLifecycleSettings base = new TwBreedingConfig.OffspringLifecycleSettings();
        setField(base, "defaultTimeToFullGrownSeconds", 7200);
        setField(config, "offspringLifecycle", base);

        TwBreedingConfig.RoleOverrideSettings roleOverride = new TwBreedingConfig.RoleOverrideSettings();
        TwBreedingConfig.OffspringLifecycleSettingsOverride lifecycleOverride =
                new TwBreedingConfig.OffspringLifecycleSettingsOverride();
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(lifecycleOverride, "defaultTimeToFullGrownSeconds", 3000);
        setField(family, "adultRoleId", "Tamed_Cow");
        setField(family, "babyRoleId", "Tamed_Cow_Calf");
        setField(family, "timeToFullGrownSeconds", 1800);
        setField(lifecycleOverride, "families", new TwBreedingConfig.RoleFamily[] { family });
        setField(roleOverride, "offspringLifecycle", lifecycleOverride);
        setRoleOverride(config, "Tamed_Cow", roleOverride);

        TwBreedingConfig.RoleFamily resolvedFamily = config.resolveLifecycleFamilyForRole("Tamed_Cow_Calf");
        assertEquals("Tamed_Cow", resolvedFamily.getAdultRoleId());
        assertEquals(1800, config.resolveOffspringLifecycle("Tamed_Cow_Calf").resolveTimeToFullGrownSeconds(resolvedFamily));
    }

    private static void setRoleOverride(TwBreedingConfig config,
                                        String roleId,
                                        TwBreedingConfig.RoleOverrideSettings override) throws Exception {
        Map<String, TwBreedingConfig.RoleOverrideSettings> map = new HashMap<>();
        map.put(roleId, override);
        setField(config, "roleOverrides", map);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

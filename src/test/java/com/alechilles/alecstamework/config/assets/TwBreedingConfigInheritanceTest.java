package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for TwBreedingConfig inheritance policy decisions. */
class TwBreedingConfigInheritanceTest {

    @Test
    void roleOverridesAreNotInheritedWhenChildOmitsSection() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.RoleOverrideSettings roleOverrideSettings = new TwBreedingConfig.RoleOverrideSettings();
        Map<String, TwBreedingConfig.RoleOverrideSettings> parentOverrides = new HashMap<>();
        parentOverrides.put("Tamed_Rat", roleOverrideSettings);

        setField(parent, "roleOverrides", parentOverrides);
        setField(child, "roleOverrides", new HashMap<String, TwBreedingConfig.RoleOverrideSettings>());

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertTrue(child.getRoleOverrides().isEmpty());
    }

    @Test
    void roleIdsContinueToInheritWhenOmitted() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        setField(parent, "roleIds", new String[] { "Tamed_Bear" });

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertArrayEquals(new String[] { "Tamed_Bear" }, child.getRoleIds());
    }

    @Test
    void cooldownMinutesAliasCountsAsExplicitNestedOverride() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.CooldownSettings parentCooldowns = new TwBreedingConfig.CooldownSettings();
        TwBreedingConfig.CooldownSettings childCooldowns = new TwBreedingConfig.CooldownSettings();
        setField(parentCooldowns, "baseCooldownSeconds", 600);
        setField(childCooldowns, "baseCooldownSeconds", 120);
        setField(parent, "cooldowns", parentCooldowns);
        setField(child, "cooldowns", childCooldowns);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Cooldowns", Set.of("BaseCooldownMinutes"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Cooldowns"), nested);

        assertEquals(120, child.getCooldowns().getBaseCooldownSeconds());
    }

    @Test
    void pairingRoleCompatibilityInheritsWhenNestedKeyOmitted() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.PairingSettings parentPairing = new TwBreedingConfig.PairingSettings();
        TwBreedingConfig.PairingSettings childPairing = new TwBreedingConfig.PairingSettings();
        setField(parentPairing, "roleCompatibility", TwBreedingConfig.RoleCompatibility.SAME_LIFECYCLE_FAMILY);
        setField(childPairing, "requireSameOwner", true);
        setField(parent, "pairing", parentPairing);
        setField(child, "pairing", childPairing);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Pairing", Set.of("RequireSameOwner"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Pairing"), nested);

        assertEquals(TwBreedingConfig.RoleCompatibility.SAME_LIFECYCLE_FAMILY,
                child.getPairing().getRoleCompatibility());
    }

    @Test
    void genderSectionInheritsMissingNestedKeys() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.GenderSettings parentGender = new TwBreedingConfig.GenderSettings();
        TwBreedingConfig.GenderSettings childGender = new TwBreedingConfig.GenderSettings();
        setField(parentGender, "enabled", true);
        setField(parentGender, "maleWeight", 2.0);
        setField(parentGender, "femaleWeight", 3.0);
        setField(childGender, "enabled", false);
        setField(childGender, "femaleWeight", 4.0);
        setField(parent, "gender", parentGender);
        setField(child, "gender", childGender);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Gender", Set.of("FemaleWeight"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Gender"), nested);

        assertTrue(child.getGender().isEnabled());
        assertEquals(2.0, child.getGender().getMaleWeight(), 0.000001);
        assertEquals(4.0, child.getGender().getFemaleWeight(), 0.000001);
    }

    @Test
    void attachmentExcludedSetsInheritWhenOmittedFromExplicitSection() throws Exception {
        TwBreedingConfig parent = breedingConfigWithExcludedSets("Saddle", "SaddleBlanket");
        TwBreedingConfig child = breedingConfigWithExcludedSets("Collar");

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Inheritance", Set.of("AttachmentInheritance", "AttachmentInheritance.ParentWeight"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Inheritance"), nested);

        assertArrayEquals(
                new String[] { "Saddle", "SaddleBlanket" },
                child.getInheritance().getAttachmentInheritance().getExcludedSets()
        );
    }

    @Test
    void explicitEmptyAttachmentExcludedSetsReplaceParentList() throws Exception {
        TwBreedingConfig parent = breedingConfigWithExcludedSets("Saddle", "SaddleBlanket");
        TwBreedingConfig child = breedingConfigWithExcludedSets();

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put(
                "Inheritance",
                Set.of("AttachmentInheritance", "AttachmentInheritance.ExcludedSets")
        );
        child.inheritMissingTopLevelFrom(parent, Set.of("Inheritance"), nested);

        assertArrayEquals(
                new String[0],
                child.getInheritance().getAttachmentInheritance().getExcludedSets()
        );
    }

    @Test
    void offspringLifecycleRoleInheritanceInheritsMissingNestedKeys() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.OffspringLifecycleSettings parentLifecycle =
                new TwBreedingConfig.OffspringLifecycleSettings();
        TwBreedingConfig.OffspringLifecycleSettings childLifecycle =
                new TwBreedingConfig.OffspringLifecycleSettings();
        TwBreedingConfig.RoleInheritanceSettings parentInheritance =
                new TwBreedingConfig.RoleInheritanceSettings();
        TwBreedingConfig.RoleInheritanceSettings childInheritance =
                new TwBreedingConfig.RoleInheritanceSettings();
        setField(parentInheritance, "mode", TwBreedingConfig.RoleInheritanceMode.PARENT_LINE);
        setField(parentInheritance, "parentWeight", 2.0);
        setField(parentInheritance, "mutationChance", 0.25);
        setField(childInheritance, "parentWeight", 4.0);
        setField(parentLifecycle, "roleInheritance", parentInheritance);
        setField(childLifecycle, "roleInheritance", childInheritance);
        setField(parent, "offspringLifecycle", parentLifecycle);
        setField(child, "offspringLifecycle", childLifecycle);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("OffspringLifecycle", Set.of("RoleInheritance", "RoleInheritance.ParentWeight"));
        child.inheritMissingTopLevelFrom(parent, Set.of("OffspringLifecycle"), nested);

        assertEquals(TwBreedingConfig.RoleInheritanceMode.PARENT_LINE,
                child.getOffspringLifecycle().getRoleInheritance().getMode());
        assertEquals(4.0, child.getOffspringLifecycle().getRoleInheritance().getParentWeight(), 0.000001);
        assertEquals(0.25, child.getOffspringLifecycle().getRoleInheritance().getMutationChance(), 0.000001);
    }

    @Test
    void genderCanBeOverriddenPerRole() throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        TwBreedingConfig.GenderSettings gender = new TwBreedingConfig.GenderSettings();
        TwBreedingConfig.RoleOverrideSettings roleOverride = new TwBreedingConfig.RoleOverrideSettings();
        TwBreedingConfig.GenderSettingsOverride genderOverride = new TwBreedingConfig.GenderSettingsOverride();
        Map<String, TwBreedingConfig.RoleOverrideSettings> overrides = new HashMap<>();
        overrides.put("Tamed_Deer_Stag", roleOverride);

        setField(gender, "enabled", false);
        setField(gender, "maleWeight", 1.0);
        setField(genderOverride, "enabled", true);
        setField(genderOverride, "maleWeight", 4.0);
        setField(roleOverride, "gender", genderOverride);
        setField(config, "gender", gender);
        setField(config, "roleOverrides", overrides);

        assertTrue(config.resolveGender("Tamed_Deer_Stag").isEnabled());
        assertEquals(4.0, config.resolveGender("Tamed_Deer_Stag").getMaleWeight(), 0.000001);
    }

    private static TwBreedingConfig breedingConfigWithExcludedSets(String... excludedSets) throws Exception {
        TwBreedingConfig config = new TwBreedingConfig();
        TwBreedingConfig.InheritanceSettings inheritance = new TwBreedingConfig.InheritanceSettings();
        TwBreedingConfig.AttachmentInheritanceSettings attachments =
                new TwBreedingConfig.AttachmentInheritanceSettings();
        setField(attachments, "excludedSets", excludedSets);
        setField(inheritance, "attachmentInheritance", attachments);
        setField(config, "inheritance", inheritance);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

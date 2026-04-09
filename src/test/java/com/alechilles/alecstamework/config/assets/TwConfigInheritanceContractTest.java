package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-config inheritance contract checks for nested-object inheritance and array/map replacement semantics. */
class TwConfigInheritanceContractTest {

    @Test
    void breedingRoleOverridesRemainLocalOnlyAndRoleIdsStillInherit() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.RoleOverrideSettings overrideSettings = new TwBreedingConfig.RoleOverrideSettings();
        Map<String, TwBreedingConfig.RoleOverrideSettings> parentOverrides = new HashMap<>();
        parentOverrides.put("Tamed_Wolf", overrideSettings);

        setField(parent, "roleIds", new String[] { "Tamed_Wolf" });
        setField(parent, "roleOverrides", parentOverrides);
        setField(child, "roleOverrides", new HashMap<String, TwBreedingConfig.RoleOverrideSettings>());

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertArrayEquals(new String[] { "Tamed_Wolf" }, child.getRoleIds());
        assertTrue(child.getRoleOverrides().isEmpty());
    }

    @Test
    void breedingPairingNestedMergeCopiesMissingFieldsOnly() throws Exception {
        TwBreedingConfig parent = new TwBreedingConfig();
        TwBreedingConfig child = new TwBreedingConfig();

        TwBreedingConfig.PairingSettings parentPairing = new TwBreedingConfig.PairingSettings();
        TwBreedingConfig.PairingSettings childPairing = new TwBreedingConfig.PairingSettings();
        setField(parentPairing, "maxNearbySameType", 9);
        setField(parentPairing, "requireSameOwner", true);
        setField(childPairing, "maxNearbySameType", 2);
        setField(childPairing, "requireSameOwner", false);

        setField(parent, "pairing", parentPairing);
        setField(child, "pairing", childPairing);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Pairing", Set.of("MaxNearbySameType"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Pairing"), nested);

        assertEquals(2, child.getPairing().getMaxNearbySameType());
        assertTrue(child.getPairing().isRequireSameOwner());
    }

    @Test
    void commandItemAllowedRolesNestedMergeAndArrayReplacementWork() throws Exception {
        TwCommandItemConfig parent = new TwCommandItemConfig();
        TwCommandItemConfig child = new TwCommandItemConfig();

        TwCommandItemConfig.AllowlistRoles parentRoles = new TwCommandItemConfig.AllowlistRoles();
        TwCommandItemConfig.AllowlistRoles childRoles = new TwCommandItemConfig.AllowlistRoles();
        setField(parentRoles, "allowlist", new String[] { "Role_A" });
        setField(childRoles, "allowlist", new String[] { "Role_B" });
        setField(parent, "allowedRoles", parentRoles);
        setField(child, "allowedRoles", childRoles);

        TwCommandItemConfig.CommandEntry[] parentCommands = new TwCommandItemConfig.CommandEntry[] {
                new TwCommandItemConfig.CommandEntry()
        };
        TwCommandItemConfig.CommandEntry[] childCommands = new TwCommandItemConfig.CommandEntry[] {
                new TwCommandItemConfig.CommandEntry()
        };
        setField(parent, "commandList", parentCommands);
        setField(child, "commandList", childCommands);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("AllowedRoles", Set.of("Mode"));
        child.inheritMissingTopLevelFrom(parent, Set.of("AllowedRoles", "CommandList"), nested);

        assertArrayEquals(new String[] { "Role_A" }, ((TwCommandItemConfig.AllowlistRoles) child.getAllowedRoles()).getAllowlist());
        assertSame(childCommands, child.getCommandList());
    }

    @Test
    void nameItemNamingNestedMergeCopiesMissingFieldsOnly() throws Exception {
        TwNameItemConfig parent = new TwNameItemConfig();
        TwNameItemConfig child = new TwNameItemConfig();

        TwNameItemConfig.NamingSettings parentNaming = new TwNameItemConfig.NamingSettings();
        TwNameItemConfig.NamingSettings childNaming = new TwNameItemConfig.NamingSettings();
        setField(parentNaming, "minLength", 4);
        setField(parentNaming, "maxLength", 30);
        setField(parentNaming, "randomNamesId", "TwNames_Default");
        setField(childNaming, "minLength", 2);
        setField(childNaming, "maxLength", 10);
        setField(childNaming, "randomNamesId", "TwNames_Custom");

        setField(parent, "naming", parentNaming);
        setField(child, "naming", childNaming);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Naming", Set.of("MinLength"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Naming"), nested);

        assertEquals(2, child.getNaming().getMinLength());
        assertEquals(30, child.getNaming().getMaxLength());
        assertEquals("TwNames_Default", child.getNaming().getRandomNamesId());
    }

    @Test
    void coopCapturePolicyNestedMergeCopiesMissingFieldsOnly() throws Exception {
        TwCoopConfig parent = new TwCoopConfig();
        TwCoopConfig child = new TwCoopConfig();

        TwCoopConfig.CapturePolicySettings parentPolicy = new TwCoopConfig.CapturePolicySettings();
        TwCoopConfig.CapturePolicySettings childPolicy = new TwCoopConfig.CapturePolicySettings();
        setField(parentPolicy, "requireTamed", true);
        setField(parentPolicy, "ownerRestricted", true);
        setField(childPolicy, "requireTamed", false);
        setField(childPolicy, "ownerRestricted", false);

        setField(parent, "capturePolicy", parentPolicy);
        setField(child, "capturePolicy", childPolicy);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("CapturePolicy", Set.of("RequireTamed"));
        child.inheritMissingTopLevelFrom(parent, Set.of("CapturePolicy"), nested);

        assertFalse(child.getCapturePolicy().isRequireTamed());
        assertTrue(child.getCapturePolicy().isOwnerRestricted());
    }

    @Test
    void coopLifecycleNestedMergeAndArrayReplacementWork() throws Exception {
        TwCoopConfig parent = new TwCoopConfig();
        TwCoopConfig child = new TwCoopConfig();

        TwCoopConfig.LifecycleRules parentLifecycle = new TwCoopConfig.LifecycleRules();
        TwCoopConfig.LifecycleRules childLifecycle = new TwCoopConfig.LifecycleRules();
        String[] childAccepted = new String[] { "role.child" };
        setField(parentLifecycle, "maxResidents", 9);
        setField(parentLifecycle, "captureWildNPCsInRange", false);
        setField(parentLifecycle, "acceptedRoleIds", new String[] { "role.parent" });
        setField(childLifecycle, "maxResidents", 2);
        setField(childLifecycle, "acceptedRoleIds", childAccepted);
        setField(parent, "lifecycleRules", parentLifecycle);
        setField(child, "lifecycleRules", childLifecycle);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("LifecycleRules", Set.of("AcceptedRoleIds"));
        child.inheritMissingTopLevelFrom(parent, Set.of("LifecycleRules"), nested);

        assertEquals(9, child.getLifecycleRules().getMaxResidents());
        assertFalse(child.getLifecycleRules().isCaptureWildNPCsInRange());
        assertSame(childAccepted, child.getLifecycleRules().getAcceptedRoleIds());
    }

    @Test
    void coopProduceNestedMergeAndMapReplacementWork() throws Exception {
        TwCoopConfig parent = new TwCoopConfig();
        TwCoopConfig child = new TwCoopConfig();

        TwCoopConfig.ProduceRules parentProduce = new TwCoopConfig.ProduceRules();
        TwCoopConfig.ProduceRules childProduce = new TwCoopConfig.ProduceRules();
        Map<String, String> parentDrops = Map.of("Role_A", "Drop_A");
        Map<String, String> childDrops = Map.of("Role_B", "Drop_B");
        setField(parentProduce, "dropsByRole", parentDrops);
        setField(parentProduce, "intervalGameHours", 4);
        setField(parentProduce, "itemsPerTick", 3);
        setField(childProduce, "dropsByRole", childDrops);
        setField(childProduce, "intervalGameHours", 1);
        setField(childProduce, "itemsPerTick", 1);
        setField(parent, "produceRules", parentProduce);
        setField(child, "produceRules", childProduce);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("ProduceRules", Set.of("DropsByRole"));
        child.inheritMissingTopLevelFrom(parent, Set.of("ProduceRules"), nested);

        assertSame(childDrops, child.getProduceRules().getDropsByRole());
        assertEquals(4, child.getProduceRules().getIntervalGameHours());
        assertEquals(3, child.getProduceRules().getItemsPerTick());
    }

    @Test
    void coopIdentityNestedMergeCopiesMissingFieldsOnly() throws Exception {
        TwCoopConfig parent = new TwCoopConfig();
        TwCoopConfig child = new TwCoopConfig();

        TwCoopConfig.IdentityRules parentIdentity = new TwCoopConfig.IdentityRules();
        TwCoopConfig.IdentityRules childIdentity = new TwCoopConfig.IdentityRules();
        setField(parentIdentity, "requireSnapshotOnRelease", false);
        setField(parentIdentity, "preserveUUID", true);
        setField(childIdentity, "requireSnapshotOnRelease", true);
        setField(childIdentity, "preserveUUID", false);
        setField(parent, "identityRules", parentIdentity);
        setField(child, "identityRules", childIdentity);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("IdentityRules", Set.of("RequireSnapshotOnRelease"));
        child.inheritMissingTopLevelFrom(parent, Set.of("IdentityRules"), nested);

        assertTrue(child.getIdentityRules().isRequireSnapshotOnRelease());
        assertTrue(child.getIdentityRules().isPreserveUUID());
    }

    @Test
    void happinessModifierNestedMergeAndBandsReplacementWork() throws Exception {
        TwHappinessConfig parent = new TwHappinessConfig();
        TwHappinessConfig child = new TwHappinessConfig();

        TwHappinessConfig.ModifierSettings parentModifiers = new TwHappinessConfig.ModifierSettings();
        TwHappinessConfig.ModifierSettings childModifiers = new TwHappinessConfig.ModifierSettings();
        TwHappinessConfig.NeedModifierSettings parentHunger = new TwHappinessConfig.NeedModifierSettings();
        TwHappinessConfig.NeedModifierSettings childHunger = new TwHappinessConfig.NeedModifierSettings();
        TwHappinessConfig.NeedBandSettings parentBand = new TwHappinessConfig.NeedBandSettings();
        TwHappinessConfig.NeedBandSettings childBand = new TwHappinessConfig.NeedBandSettings();

        setField(parentHunger, "enabled", false);
        setField(parentHunger, "bands", new TwHappinessConfig.NeedBandSettings[] { parentBand });
        setField(childHunger, "enabled", true);
        setField(childHunger, "bands", new TwHappinessConfig.NeedBandSettings[] { childBand });
        setField(parentModifiers, "hunger", parentHunger);
        setField(childModifiers, "hunger", childHunger);
        setField(parentModifiers, "ownerNearbyOffset", 8.0d);
        setField(childModifiers, "ownerNearbyOffset", 1.0d);

        setField(parent, "modifiers", parentModifiers);
        setField(child, "modifiers", childModifiers);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Modifiers", Set.of("Hunger", "Hunger.Enabled"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Modifiers"), nested);

        assertTrue(child.getModifiers().getHunger().isEnabled());
        assertSame(parentHunger.getBands(), child.getModifiers().getHunger().getBands());
        assertEquals(8.0d, child.getModifiers().getOwnerNearbyOffset(), 0.00001d);
    }

    @Test
    void happinessImpulsesFeedItemMapReplacesParentWhenExplicit() throws Exception {
        TwHappinessConfig parent = new TwHappinessConfig();
        TwHappinessConfig child = new TwHappinessConfig();

        TwHappinessConfig.ImpulseSettings parentImpulses = new TwHappinessConfig.ImpulseSettings();
        TwHappinessConfig.ImpulseSettings childImpulses = new TwHappinessConfig.ImpulseSettings();
        setField(parentImpulses, "gainOnFeed", 7.0d);
        setField(parentImpulses, "handFeedDurationMinutes", 20.0d);
        setField(parentImpulses, "feedImpulseDurationMinutes", 30.0d);
        setField(parentImpulses, "feedItemImpulses", Map.of("Tw_Parent_Feed", 9.0d));
        setField(parentImpulses, "feedParamImpulses", Map.of("FoodFavorite", 6.0d));
        setField(childImpulses, "gainOnFeed", 2.0d);
        setField(childImpulses, "feedItemImpulses", Map.of("Tw_Child_Feed", -10.0d));
        setField(childImpulses, "feedParamImpulses", Map.of("FoodGeneric", -5.0d));
        setField(parent, "impulses", parentImpulses);
        setField(child, "impulses", childImpulses);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Impulses", Set.of("FeedItemImpulses", "FeedParamImpulses"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Impulses"), nested);

        assertEquals(7.0d, child.getImpulses().getGainOnFeed(), 0.00001d);
        assertEquals(20.0d, child.getImpulses().getHandFeedDurationMinutes(), 0.00001d);
        assertEquals(30.0d, child.getImpulses().getFeedImpulseDurationMinutes(), 0.00001d);
        Map<String, Double> resolved = child.getImpulses().getFeedItemImpulses();
        assertEquals(-10.0d, resolved.get("tw_child_feed"), 0.00001d);
        assertFalse(resolved.containsKey("tw_parent_feed"));
        Map<String, Double> resolvedParams = child.getImpulses().getFeedParamImpulses();
        assertEquals(-5.0d, resolvedParams.get("FoodGeneric"), 0.00001d);
        assertFalse(resolvedParams.containsKey("FoodFavorite"));
    }

    @Test
    void needsPassiveRefillNestedMergeAndArrayReplacementWork() throws Exception {
        TwNeedsConfig parent = new TwNeedsConfig();
        TwNeedsConfig child = new TwNeedsConfig();

        TwNeedsConfig.PassiveRefillSettings parentPassive = new TwNeedsConfig.PassiveRefillSettings();
        TwNeedsConfig.PassiveRefillSettings childPassive = new TwNeedsConfig.PassiveRefillSettings();
        setField(parentPassive, "sweepIntervalSeconds", 45);
        setField(parentPassive, "containerFoodItemIds", new String[] { "item.parent" });
        setField(childPassive, "sweepIntervalSeconds", 10);
        setField(childPassive, "containerFoodItemIds", new String[] { "item.child" });
        setField(parent, "passiveRefill", parentPassive);
        setField(child, "passiveRefill", childPassive);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("PassiveRefill", Set.of("ContainerFoodItemIds"));
        child.inheritMissingTopLevelFrom(parent, Set.of("PassiveRefill"), nested);

        assertEquals(45, child.getPassiveRefill().getSweepIntervalSeconds());
        assertArrayEquals(new String[] { "item.child" }, child.getPassiveRefill().getContainerFoodItemIds());
    }

    @Test
    void spawnerCaptureNestedMergeAndMapReplacementWork() throws Exception {
        TwSpawnerConfig parent = new TwSpawnerConfig();
        TwSpawnerConfig child = new TwSpawnerConfig();

        TwSpawnerConfig.CaptureSettings parentCapture = new TwSpawnerConfig.CaptureSettings();
        TwSpawnerConfig.CaptureSettings childCapture = new TwSpawnerConfig.CaptureSettings();
        setField(parentCapture, "requireOwner", Boolean.TRUE);
        setField(parentCapture, "maxDistance", 12.0d);
        setField(childCapture, "requireOwner", Boolean.FALSE);
        setField(childCapture, "maxDistance", 3.0d);
        setField(parent, "capture", parentCapture);
        setField(child, "capture", childCapture);

        Map<String, TwSpawnerConfig.SpawnerIconOverride[]> parentByRole = Map.of("Role_A", new TwSpawnerConfig.SpawnerIconOverride[0]);
        Map<String, TwSpawnerConfig.SpawnerIconOverride[]> childByRole = Map.of("Role_B", new TwSpawnerConfig.SpawnerIconOverride[0]);
        setField(parent, "iconOverridesByRole", parentByRole);
        setField(child, "iconOverridesByRole", childByRole);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Capture", Set.of("MaxDistance"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Capture", "IconOverridesByRole"), nested);

        Field requireOwnerField = TwSpawnerConfig.CaptureSettings.class.getDeclaredField("requireOwner");
        requireOwnerField.setAccessible(true);
        assertEquals(Boolean.TRUE, requireOwnerField.get(childCapture));
        assertEquals(3.0d, getDoubleField(childCapture, "maxDistance"), 0.00001d);
        assertSame(childByRole, getField(child, "iconOverridesByRole"));
    }

    @Test
    void traitSelectionNestedMergeAndTraitsReplacementWork() throws Exception {
        TwTraitConfig parent = new TwTraitConfig();
        TwTraitConfig child = new TwTraitConfig();

        TwTraitConfig.SelectionSettings parentSelection = new TwTraitConfig.SelectionSettings();
        TwTraitConfig.SelectionSettings childSelection = new TwTraitConfig.SelectionSettings();
        TwTraitConfig.RollCountWeights parentWeights = new TwTraitConfig.RollCountWeights();
        TwTraitConfig.RollCountWeights childWeights = new TwTraitConfig.RollCountWeights();

        setField(parentSelection, "maxTraitsPerNpc", 5);
        setField(childSelection, "maxTraitsPerNpc", 2);
        setField(parentWeights, "count1", 0.55d);
        setField(childWeights, "count1", 0.11d);
        setField(parentSelection, "rollCountWeights", parentWeights);
        setField(childSelection, "rollCountWeights", childWeights);
        setField(parent, "selection", parentSelection);
        setField(child, "selection", childSelection);

        TwTraitConfig.TraitDefinition parentTrait = new TwTraitConfig.TraitDefinition();
        TwTraitConfig.TraitDefinition childTrait = new TwTraitConfig.TraitDefinition();
        TwTraitConfig.TraitDefinition[] childTraits = new TwTraitConfig.TraitDefinition[] { childTrait };
        setField(parent, "traits", new TwTraitConfig.TraitDefinition[] { parentTrait });
        setField(child, "traits", childTraits);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Selection", Set.of("RollCountWeights", "RollCountWeights.Count1"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Selection", "Traits"), nested);

        assertEquals(5, child.getSelection().getMaxTraitsPerNpc());
        assertEquals(0.11d, child.getSelection().getRollCountWeights().getCount1(), 0.00001d);
        assertSame(childTraits, child.getTraits());
    }

    @Test
    void interactionCooldownNestedMergeCopiesMissingFieldsOnly() throws Exception {
        TwInteractionConfig parent = new TwInteractionConfig();
        TwInteractionConfig child = new TwInteractionConfig();

        TwInteractionConfig.Cooldowns parentCooldowns = new TwInteractionConfig.Cooldowns();
        TwInteractionConfig.Cooldowns childCooldowns = new TwInteractionConfig.Cooldowns();
        setField(parentCooldowns, "interactionSeconds", 12);
        setField(childCooldowns, "interactionSeconds", 3);
        setField(parent, "cooldowns", parentCooldowns);
        setField(child, "cooldowns", childCooldowns);

        Map<String, Set<String>> nested = new HashMap<>();
        nested.put("Cooldowns", Set.of());
        child.inheritMissingTopLevelFrom(parent, Set.of("Cooldowns"), nested);

        assertEquals(12, child.getCooldowns().getInteractionSeconds());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static double getDoubleField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getDouble(target);
    }
}

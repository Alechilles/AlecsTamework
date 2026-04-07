package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies parent fallback behavior for sectioned global config inheritance. */
class TwGlobalConfigInheritanceTest {

    @Test
    void nestedExplicitKeysAllowPartialSectionInheritance() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "enabled", false);
        setField(parent, "priority", 99);
        setField(parent, "blockOwnerDamage", true);
        setField(parent, "blockAllPlayerDamageIfOwned", true);
        setField(parent, "invulnerableIfOwned", true);
        setField(parent, "interactionConfigParam", "parent.config");
        setField(parent, "lovedItemsParam", "parent.loved");
        setField(parent, "commandReturnHomeTeleportDistance", 111.0d);
        setField(parent, "commandReturnHomeTeleportDelayMs", 999);

        setField(child, "enabled", true);
        setField(child, "priority", 5);
        setField(child, "blockOwnerDamage", false);
        setField(child, "interactionConfigParam", "child.config");
        setField(child, "commandReturnHomeTeleportDelayMs", 100);

        Set<String> explicitTopLevelKeys = Set.of("General", "OwnershipProtection", "InteractionDefaults", "Command");
        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("General", Set.of("Priority"));
        explicitNestedKeysByTopLevel.put("OwnershipProtection", Set.of("BlockOwnerDamage"));
        explicitNestedKeysByTopLevel.put("InteractionDefaults", Set.of("InteractionConfigParam"));
        explicitNestedKeysByTopLevel.put("Command", Set.of("ReturnHomeTeleportDelayMs"));

        child.inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, explicitNestedKeysByTopLevel);

        assertFalse(child.isEnabled());
        assertEquals(5, child.getPriority());

        assertFalse(child.isBlockOwnerDamage());
        assertTrue(child.isBlockAllPlayerDamageIfOwned());
        assertTrue(child.isInvulnerableIfOwned());

        assertEquals("child.config", child.getInteractionConfigParam());
        assertEquals("parent.loved", child.getLovedItemsParam());

        assertEquals(111.0d, child.getCommandReturnHomeTeleportDistance(), 0.0001d);
        assertEquals(100, child.getCommandReturnHomeTeleportDelayMs());
    }

    @Test
    void explicitTopLevelWithoutNestedKeysPreservesLegacyBehavior() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "enabled", false);
        setField(parent, "priority", 99);

        setField(child, "enabled", true);
        setField(child, "priority", 5);

        child.inheritMissingTopLevelFrom(parent, Set.of("General"));

        assertTrue(child.isEnabled());
        assertEquals(5, child.getPriority());
    }

    @Test
    void deadRespawnCooldownMinutesKeyCountsAsExplicitCooldownOverride() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "commandDeadRespawnCooldownMs", 240000);
        setField(child, "commandDeadRespawnCooldownMs", 90000);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("Command", Set.of("DeadRespawnCooldownMins"));

        child.inheritMissingTopLevelFrom(parent, Set.of("Command"), explicitNestedKeysByTopLevel);

        assertEquals(90000, child.getCommandDeadRespawnCooldownMs());
    }

    @Test
    void defaultInteractionDefaultsAreAvailableWithoutInteractionSection() {
        TwGlobalConfig config = new TwGlobalConfig();

        assertEquals("InteractionConfigId", config.getInteractionConfigParam());
        assertEquals("LovedItems", config.getLovedItemsParam());
        assertEquals("IsHarvestable", config.getIsHarvestableParam());
        assertEquals("IsMountable", config.getIsMountableParam());
        assertEquals("HarvestInteractionContext", config.getHarvestContextParam());
        assertEquals("Harvest_Ready", config.getHarvestAlarmName());
        assertEquals("TameworkInteract_Cooldown", config.getInteractionCooldownAlarmPrefix());
    }

    @Test
    void assetSetsInheritanceIncludesFeedFamilies() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "herbivoreFeedAssetSetEnabled", true);
        setField(parent, "carnivoreFeedAssetSetEnabled", true);
        setField(child, "herbivoreFeedAssetSetEnabled", false);
        setField(child, "carnivoreFeedAssetSetEnabled", false);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("AssetSets", Set.of("HerbivoreFeed"));

        child.inheritMissingTopLevelFrom(parent, Set.of("AssetSets"), explicitNestedKeysByTopLevel);

        assertFalse(child.isHerbivoreFeedAssetSetEnabled());
        assertTrue(child.isCarnivoreFeedAssetSetEnabled());
    }

    @Test
    void simpleClaimsBreedingDefaultsAreDisabledAndSafe() {
        TwGlobalConfig config = new TwGlobalConfig();

        assertEquals(0, config.getPopulationLimitPerPlayerOwnedTotal());
        assertEquals(TwGlobalConfig.PerPlayerLimitScope.PER_WORLD, config.getPopulationPerPlayerLimitScope());
        assertFalse(config.isSimpleClaimsEnabled());
        assertFalse(config.isSimpleClaimsBreedingRequiresClaim());
        assertEquals(0, config.getSimpleClaimsBreedingLimitPerClaimChunk());
        assertEquals(0, config.getSimpleClaimsBreedingLimitPerClaimTotal());
        assertFalse(config.isSimpleClaimsDamageProtectTamedFromNonMembers());
        assertEquals("tamework.damage_tamed_claim_npc", config.getSimpleClaimsDamageAllowDamagePermissionKey());
    }

    @Test
    void ownershipRequirementsDefaultsPreserveLegacyBehavior() {
        TwGlobalConfig config = new TwGlobalConfig();

        assertFalse(config.isOwnershipCaptureRequiresOwner());
        assertFalse(config.isOwnershipSpawnRequiresOwner());
        assertTrue(config.isOwnershipInteractionRequiresOwner());
        assertTrue(config.isOwnershipLinkingRequiresOwner());
    }

    @Test
    void ownershipRequirementsInheritanceSupportsPartialNestedOverrides() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "ownershipCaptureRequiresOwner", true);
        setField(parent, "ownershipSpawnRequiresOwner", true);
        setField(parent, "ownershipInteractionRequiresOwner", false);
        setField(parent, "ownershipLinkingRequiresOwner", false);

        setField(child, "ownershipCaptureRequiresOwner", false);
        setField(child, "ownershipSpawnRequiresOwner", false);
        setField(child, "ownershipInteractionRequiresOwner", true);
        setField(child, "ownershipLinkingRequiresOwner", true);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put(
                "OwnershipRequirements",
                Set.of("CaptureRequiresOwner", "InteractionRequiresOwner")
        );

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("OwnershipRequirements"),
                explicitNestedKeysByTopLevel
        );

        assertFalse(child.isOwnershipCaptureRequiresOwner());
        assertTrue(child.isOwnershipSpawnRequiresOwner());
        assertTrue(child.isOwnershipInteractionRequiresOwner());
        assertFalse(child.isOwnershipLinkingRequiresOwner());
    }

    @Test
    void populationSectionInheritanceSupportsPartialNestedOverrides() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "populationLimitPerPlayerOwnedTotal", 24);
        setField(parent, "populationPerPlayerLimitScope", TwGlobalConfig.PerPlayerLimitScope.GLOBAL);
        setField(child, "populationLimitPerPlayerOwnedTotal", 8);
        setField(child, "populationPerPlayerLimitScope", TwGlobalConfig.PerPlayerLimitScope.PER_WORLD);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("Population", Set.of("LimitPerPlayerOwnedTotal"));

        child.inheritMissingTopLevelFrom(parent, Set.of("Population"), explicitNestedKeysByTopLevel);

        assertEquals(8, child.getPopulationLimitPerPlayerOwnedTotal());
        assertEquals(TwGlobalConfig.PerPlayerLimitScope.GLOBAL, child.getPopulationPerPlayerLimitScope());
    }

    @Test
    void legacyBreedingPopulationKeysAreIgnored() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "populationLimitPerPlayerOwnedTotal", 24);
        setField(parent, "populationPerPlayerLimitScope", TwGlobalConfig.PerPlayerLimitScope.GLOBAL);
        setField(child, "populationLimitPerPlayerOwnedTotal", 8);
        setField(child, "populationPerPlayerLimitScope", TwGlobalConfig.PerPlayerLimitScope.PER_WORLD);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("Breeding", Set.of("LimitPerPlayerTotal"));

        child.inheritMissingTopLevelFrom(parent, Set.of("Breeding"), explicitNestedKeysByTopLevel);

        assertEquals(24, child.getPopulationLimitPerPlayerOwnedTotal());
        assertEquals(TwGlobalConfig.PerPlayerLimitScope.GLOBAL, child.getPopulationPerPlayerLimitScope());
    }

    @Test
    void perPlayerScopeParsingFallsBackToPerWorldForInvalidOrBlankValues() {
        assertEquals(
                TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                TwGlobalConfig.PerPlayerLimitScope.fromConfigValue(null)
        );
        assertEquals(
                TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                TwGlobalConfig.PerPlayerLimitScope.fromConfigValue("  ")
        );
        assertEquals(
                TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                TwGlobalConfig.PerPlayerLimitScope.fromConfigValue("invalid")
        );
        assertEquals(
                TwGlobalConfig.PerPlayerLimitScope.GLOBAL,
                TwGlobalConfig.PerPlayerLimitScope.fromConfigValue("global")
        );
    }

    @Test
    void simpleClaimsBreedingChunkCapScalesAndClampsOverflow() throws Exception {
        TwGlobalConfig config = TwGlobalConfig.defaultConfig();
        setField(config, "simpleClaimsBreedingLimitPerClaimChunk", 3);
        assertEquals(0, config.resolveSimpleClaimsBreedingLimitPerClaimChunkCap(0));
        assertEquals(9, config.resolveSimpleClaimsBreedingLimitPerClaimChunkCap(3));

        setField(config, "simpleClaimsBreedingLimitPerClaimChunk", Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, config.resolveSimpleClaimsBreedingLimitPerClaimChunkCap(2));
    }

    @Test
    void simpleClaimsInheritanceAllowsSimpleClaimsEnabledOverrideWithoutBreedingOverride() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "simpleClaimsSectionDefined", true);
        setField(parent, "simpleClaimsEnabled", true);
        setField(parent, "simpleClaimsBreedingLimitPerClaimChunk", 3);
        setField(parent, "simpleClaimsBreedingLimitPerClaimTotal", 40);
        setField(parent, "simpleClaimsBreedingRequiresClaim", true);

        setField(child, "simpleClaimsSectionDefined", true);
        setField(child, "simpleClaimsEnabled", false);
        setField(child, "simpleClaimsBreedingLimitPerClaimChunk", 0);
        setField(child, "simpleClaimsBreedingLimitPerClaimTotal", 0);
        setField(child, "simpleClaimsBreedingRequiresClaim", false);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("SimpleClaims", Set.of("SimpleClaimsEnabled"));

        child.inheritMissingTopLevelFrom(parent, Set.of("SimpleClaims"), explicitNestedKeysByTopLevel);

        assertFalse(child.isSimpleClaimsEnabled());
        assertEquals(3, child.getSimpleClaimsBreedingLimitPerClaimChunk());
        assertEquals(40, child.getSimpleClaimsBreedingLimitPerClaimTotal());
        assertTrue(child.isSimpleClaimsBreedingRequiresClaim());
    }

    @Test
    void simpleClaimsInheritanceAllowsPartialNestedDamageOverrides() throws Exception {
        TwGlobalConfig parent = new TwGlobalConfig();
        TwGlobalConfig child = new TwGlobalConfig();

        setField(parent, "simpleClaimsSectionDefined", true);
        setField(parent, "simpleClaimsDamageProtectTamedFromNonMembers", true);
        setField(parent, "simpleClaimsDamageAllowDamagePermissionKey", "parent.permission");

        setField(child, "simpleClaimsSectionDefined", true);
        setField(child, "simpleClaimsDamageProtectTamedFromNonMembers", false);
        setField(child, "simpleClaimsDamageAllowDamagePermissionKey", "child.permission");

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put(
                "SimpleClaims",
                Set.of("Damage", "Damage.ProtectTamedFromNonMembers")
        );

        child.inheritMissingTopLevelFrom(parent, Set.of("SimpleClaims"), explicitNestedKeysByTopLevel);

        assertFalse(child.isSimpleClaimsDamageProtectTamedFromNonMembers());
        assertEquals("parent.permission", child.getSimpleClaimsDamageAllowDamagePermissionKey());
    }

    @Test
    void selectBestSimpleClaimsCandidateIgnoresSectionlessConfigsAndPrefersEnabledOnTie() throws Exception {
        TwGlobalConfig sectionless = TwGlobalConfig.defaultConfig();
        setField(sectionless, "id", "TwGlobalConfig_AnimalHusbandry");
        setField(sectionless, "enabled", true);
        setField(sectionless, "priority", 0);

        TwGlobalConfig disabledSimpleClaims = TwGlobalConfig.defaultConfig();
        setField(disabledSimpleClaims, "id", "TwGlobalConfig_Default");
        setField(disabledSimpleClaims, "enabled", true);
        setField(disabledSimpleClaims, "priority", 0);
        setField(disabledSimpleClaims, "simpleClaimsSectionDefined", true);
        setField(disabledSimpleClaims, "simpleClaimsEnabled", false);

        TwGlobalConfig enabledSimpleClaims = TwGlobalConfig.defaultConfig();
        setField(enabledSimpleClaims, "id", "TwGlobalConfig_Test");
        setField(enabledSimpleClaims, "enabled", true);
        setField(enabledSimpleClaims, "priority", 0);
        setField(enabledSimpleClaims, "simpleClaimsSectionDefined", true);
        setField(enabledSimpleClaims, "simpleClaimsEnabled", true);

        TwGlobalConfig selected = TwGlobalConfig.selectBestSimpleClaimsCandidate(
                List.of(sectionless, disabledSimpleClaims, enabledSimpleClaims)
        );

        assertEquals(enabledSimpleClaims, selected);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

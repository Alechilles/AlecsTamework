package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
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

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

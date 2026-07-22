package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies parent fallback behavior for role-scoped companion config inheritance. */
class TwCompanionConfigInheritanceTest {

    @Test
    void deadRespawnCooldownMinutesKeyCountsAsExplicitCooldownOverride() throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();

        setNestedIntField(parent, "command", "deadRespawnCooldownMs", 180000);
        setNestedIntField(child, "command", "deadRespawnCooldownMs", 45000);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("Command", Set.of("DeadRespawnCooldownMins"));

        child.inheritMissingTopLevelFrom(parent, Set.of("Command"), explicitNestedKeysByTopLevel);

        assertEquals(45000, child.getCommand().getDeadRespawnCooldownMs());
    }

    @Test
    void commandTravelFallsBackFromParentWhenTravelKeyIsOmitted() throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();

        Object parentTravel = getNestedObject(parent, "command", "travel");
        Object childTravel = getNestedObject(child, "command", "travel");
        setEnumField(parentTravel, "onTransferFailure", TwCompanionConfig.TransferFailurePolicy.MarkLost);
        setEnumField(childTravel, "onTransferFailure", TwCompanionConfig.TransferFailurePolicy.Ignore);
        setBooleanField(parentTravel, "crossWorldRecallEnabled", true);
        setBooleanField(childTravel, "crossWorldRecallEnabled", false);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("Command", Set.of("RecallSafeSpawnDistance"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Command"), explicitNestedKeysByTopLevel);

        assertEquals(
                TwCompanionConfig.TransferFailurePolicy.MarkLost,
                child.getCommand().getTravel().getOnTransferFailure()
        );
        assertTrue(child.getCommand().getTravel().isCrossWorldRecallEnabled());
    }

    @Test
    void commandTravelSupportsNestedPartialOverridesWithinTravelObject() throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();

        Object parentTravel = getNestedObject(parent, "command", "travel");
        Object childTravel = getNestedObject(child, "command", "travel");
        setEnumField(parentTravel, "onTransferFailure", TwCompanionConfig.TransferFailurePolicy.MarkLost);
        setEnumField(childTravel, "onTransferFailure", TwCompanionConfig.TransferFailurePolicy.Ignore);
        setBooleanField(parentTravel, "crossWorldRecallEnabled", true);
        setBooleanField(childTravel, "crossWorldRecallEnabled", false);

        Map<String, Set<String>> explicitNestedKeysByTopLevel = new HashMap<>();
        explicitNestedKeysByTopLevel.put("Command", Set.of("Travel", "Travel.OnTransferFailure"));
        child.inheritMissingTopLevelFrom(parent, Set.of("Command"), explicitNestedKeysByTopLevel);

        assertEquals(TwCompanionConfig.TransferFailurePolicy.Ignore, child.getCommand().getTravel().getOnTransferFailure());
        assertTrue(child.getCommand().getTravel().isCrossWorldRecallEnabled());
    }

    @Test
    void effectiveSettingsUseGlobalOwnershipProtectionWhenScopedConfigExists() throws Exception {
        TwCompanionConfig scoped = new TwCompanionConfig();
        setNestedBooleanField(scoped, "ownershipProtection", "blockOwnerDamage", false);
        setNestedBooleanField(scoped, "ownershipProtection", "blockAllPlayerDamageIfOwned", false);
        setNestedBooleanField(scoped, "ownershipProtection", "invulnerableIfOwned", false);

        TwGlobalConfig global = TwGlobalConfig.defaultConfig();
        setBooleanField(global, "blockOwnerDamage", true);
        setBooleanField(global, "blockAllPlayerDamageIfOwned", true);
        setBooleanField(global, "invulnerableIfOwned", true);

        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.EffectiveSettings.from(scoped, global);

        assertTrue(settings.isBlockOwnerDamage());
        assertTrue(settings.isBlockAllPlayerDamageIfOwned());
        assertTrue(settings.isInvulnerableIfOwned());
    }

    @Test
    void reviveNestedFieldsInheritWhileExplicitCostsReplace() throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();
        Object parentRevive = getNestedObject(parent, "command", "revive");
        Object childRevive = getNestedObject(child, "command", "revive");
        setBooleanField(parentRevive, "enabled", false);
        setLongField(parentRevive, "gameplayCooldownMs", 120_000L);
        setField(parentRevive, "costs", new TwItemCostComponent[] {
                new TwItemCostComponent("Life_Essence", 3)
        });
        setBooleanField(childRevive, "enabled", true);
        setLongField(childRevive, "gameplayCooldownMs", 1L);
        setField(childRevive, "costs", new TwItemCostComponent[] {
                new TwItemCostComponent("Dragon_Essence", 2),
                new TwItemCostComponent("Gold_Bar", 5)
        });

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of("Command", Set.of("Revive", "Revive.Costs"))
        );

        TwCompanionConfig.ReviveSettings result = child.getCommand().getRevive();
        assertFalse(result.isEnabled());
        assertEquals(120_000L, result.getGameplayCooldownMs());
        assertEquals(2, result.getCosts().length);
        assertEquals("Dragon_Essence", result.getCosts()[0].getItemId());
        assertEquals("Gold_Bar", result.getCosts()[1].getItemId());
    }

    @Test
    void summonNestedFieldsAndWarningReplacementFollowContract() throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();
        Object parentSummon = getNestedObject(parent, "command", "summon");
        Object childSummon = getNestedObject(child, "command", "summon");
        setBooleanField(parentSummon, "enabled", true);
        setLongField(parentSummon, "activeDurationMs", 600_000L);
        setLongField(parentSummon, "resummonCooldownMs", 60_000L);
        setField(parentSummon, "expiryWarningThresholdsMs", new Long[] { 60_000L, 10_000L });
        setLongField(childSummon, "activeDurationMs", 900_000L);
        setField(childSummon, "expiryWarningThresholdsMs", new Long[] { 30_000L });

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of("Command", Set.of("Summon", "Summon.ActiveDurationMs", "Summon.ExpiryWarningThresholdsMs"))
        );

        TwCompanionConfig.SummonSettings result = child.getCommand().getSummon();
        assertTrue(result.isEnabled());
        assertEquals(900_000L, result.getActiveDurationMs());
        assertEquals(60_000L, result.getResummonCooldownMs());
        assertArrayEquals(new long[] { 30_000L }, result.getExpiryWarningThresholdsMs());
    }

    private void setNestedIntField(Object target, String nestedFieldName, String fieldName, int value)
            throws Exception {
        Field nestedField = target.getClass().getDeclaredField(nestedFieldName);
        nestedField.setAccessible(true);
        Object nested = nestedField.get(target);

        Field field = nested.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(nested, value);
    }

    private void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private void setLongField(Object target, String fieldName, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void setNestedBooleanField(Object target, String nestedFieldName, String fieldName, boolean value)
            throws Exception {
        Field nestedField = target.getClass().getDeclaredField(nestedFieldName);
        nestedField.setAccessible(true);
        Object nested = nestedField.get(target);

        Field field = nested.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(nested, value);
    }

    private void setEnumField(Object target, String fieldName, Object enumValue) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, enumValue);
    }

    private Object getNestedObject(Object target, String nestedFieldName, String fieldName) throws Exception {
        Field nestedField = target.getClass().getDeclaredField(nestedFieldName);
        nestedField.setAccessible(true);
        Object nested = nestedField.get(target);
        Field field = nested.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(nested);
    }
}

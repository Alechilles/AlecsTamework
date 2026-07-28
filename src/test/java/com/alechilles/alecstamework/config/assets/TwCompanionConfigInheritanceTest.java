package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void effectiveSettingsKeepRoleScopedRespawnCooldown() throws Exception {
        TwCompanionConfig scoped = new TwCompanionConfig();
        setNestedIntField(
                scoped, "command", "deadRespawnCooldownMs", 600_000
        );
        TwGlobalConfig global = TwGlobalConfig.defaultConfig();
        setField(global, "commandDeadRespawnCooldownMs", 60_000);

        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.EffectiveSettings.from(scoped, global);

        assertEquals(600_000, settings.getDeadRespawnCooldownMs());
    }

    @Test
    void deadRespawnFieldsInheritDirectly() throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();
        setNestedBooleanField(
                parent, "command", "deadRespawnEnabled", false
        );
        setNestedIntField(
                parent, "command", "deadRespawnCooldownMs", 120_000
        );
        setNestedBooleanField(
                child, "command", "deadRespawnEnabled", true
        );
        setNestedIntField(
                child, "command", "deadRespawnCooldownMs", 1
        );

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of("Command", Set.of("DeadRespawnEnabled"))
        );

        assertTrue(child.getCommand().isDeadRespawnEnabled());
        assertEquals(
                120_000,
                child.getCommand().getDeadRespawnCooldownMs()
        );
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

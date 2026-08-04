package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies parent fallback behavior for role-scoped companion config inheritance. */
class TwCompanionConfigInheritanceTest {

    @Test
    void shoulderRidePartialOverrideInheritsMissingOffsets() {
        TwCompanionConfig parent = decode("""
                { "Command": { "ShoulderRide": {
                  "Enabled": true, "OffsetX": 0.4,
                  "OffsetY": 1.6, "OffsetZ": -0.1
                } } }
                """);
        TwCompanionConfig child = decode("""
                { "Command": { "ShoulderRide": { "OffsetX": 0.25 } } }
                """);

        child.inheritMissingTopLevelFrom(parent, Set.of("Command"),
                Map.of("Command", Set.of(
                        "ShoulderRide", "ShoulderRide.OffsetX")));

        TwCompanionShoulderRideSettings result =
                child.getCommand().getShoulderRide();
        assertTrue(result.isEnabled());
        assertEquals(0.25D, result.getOffsetX());
        assertEquals(1.6D, result.getOffsetY());
        assertEquals(-0.1D, result.getOffsetZ());
    }

    @Test
    void omittedShoulderRideCopiesTheParentSection() {
        TwCompanionConfig parent = decode("""
                { "Command": { "ShoulderRide": {
                  "Enabled": true, "OffsetY": 1.2
                } } }
                """);
        TwCompanionConfig child = decode("{ \"Command\": {} }");

        child.inheritMissingTopLevelFrom(parent, Set.of("Command"),
                Map.of("Command", Set.of()));

        assertTrue(child.getCommand().getShoulderRide().isConfigured());
        assertEquals(1.2D,
                child.getCommand().getShoulderRide().getOffsetY());
    }

    @Test
    void codecDecodesFlightToggleAndPreservesExplicitNestedHookOverride() {
        TwCompanionConfig parent = decode("""
                {
                  "Command": {
                    "FlightToggle": {
                      "Enabled": true,
                      "HookId": "parent.hook"
                    }
                  }
                }
                """);
        TwCompanionConfig child = decode("""
                {
                  "Command": {
                    "FlightToggle": {
                      "HookId": "  child.hook  "
                    }
                  }
                }
                """);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of("Command", Set.of(
                        "FlightToggle", "FlightToggle.HookId"
                ))
        );

        assertTrue(child.getCommand().getFlightToggle().isEnabled());
        assertEquals(
                "child.hook",
                child.getCommand().getFlightToggle().getHookId()
        );
    }

    @Test
    void omittedFlightToggleCopiesAllParentValues() throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();
        setFlightToggle(parent, true, "parent.hook");
        setFlightToggle(child, false, "child.hook");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of("Command", Set.of())
        );

        assertTrue(child.getCommand().getFlightToggle().isEnabled());
        assertEquals(
                "parent.hook",
                child.getCommand().getFlightToggle().getHookId()
        );
    }

    @Test
    void flightToggleHookOverrideInheritsParentEnabledValue() throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();
        setFlightToggle(parent, true, "parent.hook");
        setFlightToggle(child, false, "child.hook");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of("Command", Set.of(
                        "FlightToggle", "FlightToggle.HookId"
                ))
        );

        assertTrue(child.getCommand().getFlightToggle().isEnabled());
        assertEquals(
                "child.hook",
                child.getCommand().getFlightToggle().getHookId()
        );
    }

    @Test
    void flightToggleEnabledFalseExplicitlyDisablesInheritedCapability()
            throws Exception {
        TwCompanionConfig parent = new TwCompanionConfig();
        TwCompanionConfig child = new TwCompanionConfig();
        setFlightToggle(parent, true, "parent.hook");
        setFlightToggle(child, false, "child.hook");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of("Command", Set.of(
                        "FlightToggle", "FlightToggle.Enabled"
                ))
        );

        assertFalse(child.getCommand().getFlightToggle().isEnabled());
        assertEquals(
                "parent.hook",
                child.getCommand().getFlightToggle().getHookId()
        );
    }

    @Test
    void effectiveSettingsPreserveResolvedRoleScopedFlightToggle()
            throws Exception {
        TwCompanionConfig scoped = new TwCompanionConfig();
        setFlightToggle(
                scoped,
                true,
                "HyDragon.Command.ToggleAirborneMode"
        );

        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.EffectiveSettings.from(
                        scoped,
                        TwGlobalConfig.defaultConfig()
                );

        assertTrue(settings.getFlightToggle().isEnabled());
        assertEquals(
                "HyDragon.Command.ToggleAirborneMode",
                settings.getFlightToggle().getHookId()
        );
    }

    @Test
    void commandFlightToggleGetterReturnsIndependentCopy() {
        TwCompanionConfig config = decode("""
                {
                  "Command": {
                    "FlightToggle": {
                      "Enabled": true,
                      "HookId": "source.hook"
                    }
                  }
                }
                """);

        TwCompanionFlightToggleSettings returned =
                config.getCommand().getFlightToggle();
        returned.setEnabled(false);
        returned.setHookId("mutated.hook");

        TwCompanionFlightToggleSettings source =
                config.getCommand().getFlightToggle();
        assertTrue(source.isEnabled());
        assertEquals("source.hook", source.getHookId());
    }

    @Test
    void effectiveFlightToggleGetterReturnsIndependentCopy() {
        TwCompanionConfig scoped = decode("""
                {
                  "Command": {
                    "FlightToggle": {
                      "Enabled": true,
                      "HookId": "source.hook"
                    }
                  }
                }
                """);
        TwCompanionConfig.EffectiveSettings settings =
                TwCompanionConfig.EffectiveSettings.from(scoped, null);

        TwCompanionFlightToggleSettings returned = settings.getFlightToggle();
        returned.setEnabled(false);
        returned.setHookId("mutated.hook");

        TwCompanionFlightToggleSettings source = settings.getFlightToggle();
        assertTrue(source.isEnabled());
        assertEquals("source.hook", source.getHookId());
    }

    @Test
    void globalAndDefaultEffectiveSettingsKeepFlightToggleDisabled() {
        assertDisabled(
                TwCompanionConfig.EffectiveSettings.fromGlobal(null)
                        .getFlightToggle()
        );
        assertDisabled(
                TwCompanionConfig.EffectiveSettings.fromGlobal(
                        TwGlobalConfig.defaultConfig()
                ).getFlightToggle()
        );
    }

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

    private static TwCompanionConfig decode(String json) {
        return TwCompanionConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
    }

    private static void assertDisabled(
            TwCompanionFlightToggleSettings settings
    ) {
        assertFalse(settings.isEnabled());
        assertFalse(settings.isConfigured());
        assertEquals("", settings.getHookId());
    }

    private void setFlightToggle(
            TwCompanionConfig target,
            boolean enabled,
            String hookId
    ) throws Exception {
        TwCompanionFlightToggleSettings flightToggle =
                new TwCompanionFlightToggleSettings();
        flightToggle.setEnabled(enabled);
        flightToggle.setHookId(hookId);
        setNestedObjectField(target, "command", "flightToggle", flightToggle);
    }

    private void setNestedObjectField(
            Object target,
            String nestedFieldName,
            String fieldName,
            Object value
    ) throws Exception {
        Field nestedField = target.getClass().getDeclaredField(nestedFieldName);
        nestedField.setAccessible(true);
        Object nested = nestedField.get(target);
        Field field = nested.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(nested, value);
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

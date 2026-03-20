package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

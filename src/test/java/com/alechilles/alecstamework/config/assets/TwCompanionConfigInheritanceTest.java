package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private void setNestedIntField(Object target, String nestedFieldName, String fieldName, int value)
            throws Exception {
        Field nestedField = target.getClass().getDeclaredField(nestedFieldName);
        nestedField.setAccessible(true);
        Object nested = nestedField.get(target);

        Field field = nested.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(nested, value);
    }
}

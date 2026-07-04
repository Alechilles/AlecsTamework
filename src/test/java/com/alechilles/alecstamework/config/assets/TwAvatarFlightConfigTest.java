package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwAvatarFlightConfigTest {

    @Test
    void defaultConfigExposesSafePrototypeValues() {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        assertTrue(config.isEnabled());
        assertFalse(config.getModel().isApplyModel());
        assertEquals("NordicDrake", config.getModel().getModelId());
        assertEquals(750L, config.getInput().getIntentTimeoutMs());
        assertTrue(config.getMovement().getMaxForwardSpeed() > 0.0);
        assertEquals(18.0, config.getMovement().getAirbrakeDeceleration(), 0.00001);
        assertTrue(config.getJump().getCooldownSeconds() > 0.0);
    }

    @Test
    void omittedTopLevelSectionsInheritFromParent() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "movement", "maxForwardSpeed", 22.0);
        setNestedField(parent, "jump", "upwardImpulse", 9.0);

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertEquals(22.0, child.getMovement().getMaxForwardSpeed(), 0.00001);
        assertEquals(9.0, child.getJump().getUpwardImpulse(), 0.00001);
    }

    @Test
    void explicitNestedMovementSectionOnlyKeepsExplicitKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "movement", "maxForwardSpeed", 20.0);
        setNestedField(parent, "movement", "forwardAcceleration", 40.0);
        setNestedField(child, "movement", "maxForwardSpeed", 12.0);
        setNestedField(child, "movement", "forwardAcceleration", 5.0);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Movement"),
                Map.of("Movement", Set.of("MaxForwardSpeed"))
        );

        assertEquals(12.0, child.getMovement().getMaxForwardSpeed(), 0.00001);
        assertEquals(40.0, child.getMovement().getForwardAcceleration(), 0.00001);
    }

    @Test
    void explicitNestedModelSectionInheritsApplyModelWhenMissing() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "model", "applyModel", true);
        setNestedField(parent, "model", "modelId", "ParentDragon");
        setNestedField(child, "model", "modelId", "ChildDragon");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Model"),
                Map.of("Model", Set.of("ModelId"))
        );

        assertTrue(child.getModel().isApplyModel());
        assertEquals("ChildDragon", child.getModel().getModelId());
    }

    @Test
    void explicitDebugSectionInheritsMissingBooleans() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "debug", "logControllerTicks", true);
        setNestedField(parent, "debug", "logInputTransitions", true);
        setNestedField(child, "debug", "logControllerTicks", false);
        setNestedField(child, "debug", "logInputTransitions", false);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Debug"),
                Map.of("Debug", Set.of("LogControllerTicks"))
        );

        assertFalse(child.getDebug().isLogControllerTicks());
        assertTrue(child.getDebug().isLogInputTransitions());
    }

    private static void setNestedField(Object target, String nestedFieldName, String fieldName, Object value)
            throws Exception {
        Field nestedField = target.getClass().getDeclaredField(nestedFieldName);
        nestedField.setAccessible(true);
        Object nested = nestedField.get(target);
        Field field = nested.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(nested, value);
    }
}

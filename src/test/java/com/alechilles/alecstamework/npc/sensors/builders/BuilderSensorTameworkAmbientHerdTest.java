package com.alechilles.alecstamework.npc.sensors.builders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.npc.asset.builder.*;
import com.hypixel.hytale.server.npc.corecomponents.entity.builders.BuilderHeadMotionWatch;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class BuilderSensorTameworkAmbientHerdTest {
    /** Prevents herd sensors from invalidating their containing role during engine loading. */
    @Test
    void loadsEnabledGateAndProvidesTargetForNativeWatch() {
        for (boolean enabled : new boolean[] {true, false}) {
            var errors = new ArrayList<String>();
            var features = new FeatureEvaluatorHelper();
            var validation = new BuilderValidationHelper("ambient-test", features,
                    new InternalReferenceResolver(), new StateMappingHelper(),
                    new InstructionContextHelper(InstructionType.Component),
                    new ExtraInfo(), new ArrayList<>(), errors);
            var parameters = new TestParameters();
            var manager = new BuilderManager();
            var sensor = new BuilderSensorTameworkAmbientHerd();
            sensor.readConfig(null, JsonParser.parseString(
                    "{\"Type\":\"TameworkAmbientHerd\",\"Enabled\":" + enabled
                            + ",\"Phase\":\"MOVE\"}"), manager, parameters, validation);
            assertTrue(errors.isEmpty(), errors::toString);
            assertEquals(enabled, sensor.isEnabled(null));

            new BuilderHeadMotionWatch().readConfig(null,
                    JsonParser.parseString("{\"Type\":\"Watch\"}"), manager, parameters, validation);
            assertTrue(errors.isEmpty(), errors::toString);
        }
    }

    private static final class TestParameters extends BuilderParameters {
        TestParameters() {
            super(new StdScope(null), "ambient-test", null);
        }
    }
}

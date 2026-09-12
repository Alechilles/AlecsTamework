package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkFollowFormation;
import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.asset.builder.BuilderValidationHelper;
import com.hypixel.hytale.server.npc.asset.builder.FeatureEvaluatorHelper;
import com.hypixel.hytale.server.npc.asset.builder.InstructionContextHelper;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.corecomponents.movement.builders.BuilderBodyMotionFind;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowFormationValidationTest {
    @Test
    void formationPositionSatisfiesNativeSeekValidation() throws Exception {
        // Regression: Hytale rejected the follow component because Seek could not see
        // a position provider, even though the runtime sensor returned a position.
        var errors = new ArrayList<String>();
        var features = new FeatureEvaluatorHelper();
        var validation = new BuilderValidationHelper("follow-formation-test", features,
                null, null, new InstructionContextHelper(InstructionType.Default),
                new ExtraInfo(), null, errors);
        var constructor = BuilderParameters.class.getDeclaredConstructor(
                StdScope.class, String.class, String.class);
        constructor.setAccessible(true);
        var parameters = constructor.newInstance(new StdScope(null), "follow-formation-test", null);
        var manager = new BuilderManager();
        new BuilderSensorTameworkFollowFormation().readConfig(
                null, new JsonObject(), manager, parameters, validation);
        features.lock();

        var seek = new JsonObject();
        seek.addProperty("StopDistance", 0.8);
        seek.addProperty("SlowDownDistance", 3);
        // NPCPlugin registers BuilderBodyMotionFind as the native "Seek" motion.
        new BuilderBodyMotionFind().readConfig(null, seek, manager, parameters, validation);

        assertTrue(errors.isEmpty(), () -> "Follow sensor and Seek must load together: " + errors);
    }
}

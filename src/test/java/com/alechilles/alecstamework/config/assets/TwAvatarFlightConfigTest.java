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
        assertEquals(0.45, config.getBoost().getDurationSeconds(), 0.00001);
        assertEquals("FlyIdle", config.getAnimation().getIdleAnimation());
        assertEquals("Fly", config.getAnimation().getFlightAnimation());
        assertEquals("FlyFast", config.getAnimation().getFastFlightAnimation());
        assertTrue(config.getAnimation().getResendIntervalMs() > 0L);
        assertEquals("FlyIdle", config.getAnimation().animationFor(true, false));
        assertEquals("Fly", config.getAnimation().animationFor(false, false));
        assertEquals("FlyFast", config.getAnimation().animationFor(false, true));
        assertTrue(config.getAnimation().isSuppressNonMovementAnimations());
        assertTrue(config.getAnimation().isSuppressActionAnimation());
        assertTrue(config.getAnimation().isSuppressStatusAnimation());
        assertTrue(config.getAnimation().isSuppressEmoteAnimation());
        assertFalse(config.getAnimation().isSuppressFaceAnimation());
        assertTrue(config.getAnimation().getSuppressionIntervalMs() > 0L);
        assertFalse(config.getAnimation().isPoseAnimationsEnabled());
        assertEquals("Status", config.getAnimation().getPitchPoseSlot());
        assertEquals("Emote", config.getAnimation().getRollPoseSlot());
        assertEquals("", config.getAnimation().getPitchUpPoseAnimation());
        assertEquals("", config.getAnimation().getPitchDownPoseAnimation());
        assertEquals("", config.getAnimation().getBankLeftPoseAnimation());
        assertEquals("", config.getAnimation().getBankRightPoseAnimation());
        assertEquals("", config.getAnimation().pitchPoseAnimationFor(80.0));
        assertEquals("", config.getAnimation().rollPoseAnimationFor(20.0));
        assertTrue(config.getAnimation().getPoseResendIntervalMs() > 0L);
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

    @Test
    void explicitBoostSectionInheritsMissingDuration() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "boost", "forwardImpulse", 11.0);
        setNestedField(parent, "boost", "cooldownSeconds", 2.0);
        setNestedField(parent, "boost", "durationSeconds", 0.8);
        setNestedField(child, "boost", "forwardImpulse", 5.0);
        setNestedField(child, "boost", "cooldownSeconds", 0.5);
        setNestedField(child, "boost", "durationSeconds", 0.2);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Boost"),
                Map.of("Boost", Set.of("ForwardImpulse"))
        );

        assertEquals(5.0, child.getBoost().getForwardImpulse(), 0.00001);
        assertEquals(2.0, child.getBoost().getCooldownSeconds(), 0.00001);
        assertEquals(0.8, child.getBoost().getDurationSeconds(), 0.00001);
    }

    @Test
    void explicitAnimationSectionInheritsMissingNames() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "animation", "idleAnimation", "ParentIdle");
        setNestedField(parent, "animation", "flightAnimation", "ParentFly");
        setNestedField(parent, "animation", "fastFlightAnimation", "ParentFast");
        setNestedField(parent, "animation", "resendIntervalMs", 400.0);
        setNestedField(child, "animation", "idleAnimation", "ChildIdle");
        setNestedField(child, "animation", "flightAnimation", "ChildFly");
        setNestedField(child, "animation", "fastFlightAnimation", "ChildFast");
        setNestedField(child, "animation", "resendIntervalMs", 100.0);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Animation"),
                Map.of("Animation", Set.of("IdleAnimation"))
        );

        assertEquals("ChildIdle", child.getAnimation().getIdleAnimation());
        assertEquals("ParentFly", child.getAnimation().getFlightAnimation());
        assertEquals("ParentFast", child.getAnimation().getFastFlightAnimation());
        assertEquals(400L, child.getAnimation().getResendIntervalMs());
    }

    @Test
    void explicitAnimationSectionInheritsMissingSuppressionKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "animation", "suppressNonMovementAnimations", true);
        setNestedField(parent, "animation", "suppressActionAnimation", true);
        setNestedField(parent, "animation", "suppressStatusAnimation", true);
        setNestedField(parent, "animation", "suppressEmoteAnimation", true);
        setNestedField(parent, "animation", "suppressFaceAnimation", true);
        setNestedField(parent, "animation", "suppressionIntervalMs", 125.0);
        setNestedField(parent, "animation", "poseAnimationsEnabled", true);
        setNestedField(parent, "animation", "pitchPoseSlot", "Status");
        setNestedField(parent, "animation", "rollPoseSlot", "Emote");
        setNestedField(parent, "animation", "pitchUpPoseAnimation", "ParentPitchUp");
        setNestedField(parent, "animation", "pitchDownPoseAnimation", "ParentPitchDown");
        setNestedField(parent, "animation", "bankLeftPoseAnimation", "ParentBankLeft");
        setNestedField(parent, "animation", "bankRightPoseAnimation", "ParentBankRight");
        setNestedField(parent, "animation", "pitchPoseThresholdDegrees", 12.0);
        setNestedField(parent, "animation", "rollPoseThresholdDegrees", 9.0);
        setNestedField(parent, "animation", "poseResendIntervalMs", 333.0);
        setNestedField(child, "animation", "suppressNonMovementAnimations", false);
        setNestedField(child, "animation", "suppressActionAnimation", false);
        setNestedField(child, "animation", "suppressStatusAnimation", false);
        setNestedField(child, "animation", "suppressEmoteAnimation", false);
        setNestedField(child, "animation", "suppressFaceAnimation", false);
        setNestedField(child, "animation", "suppressionIntervalMs", 500.0);
        setNestedField(child, "animation", "poseAnimationsEnabled", false);
        setNestedField(child, "animation", "pitchPoseSlot", "Action");
        setNestedField(child, "animation", "rollPoseSlot", "Face");
        setNestedField(child, "animation", "pitchUpPoseAnimation", "ChildPitchUp");
        setNestedField(child, "animation", "pitchDownPoseAnimation", "ChildPitchDown");
        setNestedField(child, "animation", "bankLeftPoseAnimation", "ChildBankLeft");
        setNestedField(child, "animation", "bankRightPoseAnimation", "ChildBankRight");
        setNestedField(child, "animation", "pitchPoseThresholdDegrees", 1.0);
        setNestedField(child, "animation", "rollPoseThresholdDegrees", 2.0);
        setNestedField(child, "animation", "poseResendIntervalMs", 50.0);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Animation"),
                Map.of("Animation", Set.of("SuppressActionAnimation", "PitchPoseSlot", "PitchUpPoseAnimation"))
        );

        assertTrue(child.getAnimation().isSuppressNonMovementAnimations());
        assertFalse(child.getAnimation().isSuppressActionAnimation());
        assertTrue(child.getAnimation().isSuppressStatusAnimation());
        assertTrue(child.getAnimation().isSuppressEmoteAnimation());
        assertTrue(child.getAnimation().isSuppressFaceAnimation());
        assertEquals(125L, child.getAnimation().getSuppressionIntervalMs());
        assertTrue(child.getAnimation().isPoseAnimationsEnabled());
        assertEquals("Action", child.getAnimation().getPitchPoseSlot());
        assertEquals("Emote", child.getAnimation().getRollPoseSlot());
        assertEquals("ChildPitchUp", child.getAnimation().getPitchUpPoseAnimation());
        assertEquals("ParentPitchDown", child.getAnimation().getPitchDownPoseAnimation());
        assertEquals("ParentBankLeft", child.getAnimation().getBankLeftPoseAnimation());
        assertEquals("ParentBankRight", child.getAnimation().getBankRightPoseAnimation());
        assertEquals(12.0, child.getAnimation().getPitchPoseThresholdDegrees(), 0.00001);
        assertEquals(9.0, child.getAnimation().getRollPoseThresholdDegrees(), 0.00001);
        assertEquals(333L, child.getAnimation().getPoseResendIntervalMs());
        assertEquals("ChildPitchUp", child.getAnimation().pitchPoseAnimationFor(80.0));
        assertEquals("ParentBankLeft", child.getAnimation().rollPoseAnimationFor(-20.0));
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

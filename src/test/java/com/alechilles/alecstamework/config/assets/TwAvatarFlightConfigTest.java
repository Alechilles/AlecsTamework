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
        assertEquals(250L, config.getInput().getAirborneJumpActivationDelayMs());
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
        assertEquals("", config.getAnimation().getPitchUpBankLeftPoseAnimation());
        assertEquals("", config.getAnimation().getPitchUpBankRightPoseAnimation());
        assertEquals("", config.getAnimation().getPitchDownBankLeftPoseAnimation());
        assertEquals("", config.getAnimation().getPitchDownBankRightPoseAnimation());
        assertEquals("", config.getAnimation().pitchPoseAnimationFor(80.0));
        assertEquals("", config.getAnimation().rollPoseAnimationFor(20.0));
        assertEquals("", config.getAnimation().sharedPoseAnimationFor(80.0, 20.0));
        assertTrue(config.getAnimation().getPoseResendIntervalMs() > 0L);
        assertTrue(config.getAbilityAnimation().isEnabled());
        assertEquals("Action", config.getAbilityAnimation().getSlot());
        assertEquals("", config.getAbilityAnimation().getUpwardBoostAnimation());
        assertEquals("", config.getAbilityAnimation().getForwardBoostAnimation());
        assertEquals("", config.getAbilityAnimation().getAirbrakeAnimation());
        assertEquals(650L, config.getAbilityAnimation().getUpwardBoostDurationMs());
        assertEquals(700L, config.getAbilityAnimation().getForwardBoostDurationMs());
        assertEquals(500L, config.getAbilityAnimation().getAirbrakeDurationMs());
        assertFalse(config.getRiderVisual().isHideOwnerEquipment());
        assertFalse(config.getRiderVisual().isHideOwnerArmor());
        assertFalse(config.getRiderVisual().isHideOwnerHands());
        assertFalse(config.getRiderVisual().isShowRider());
    }

    @Test
    void animationResendCanBeDisabledForModelAuthoredSoundTiming() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setNestedField(config, "animation", "resendIntervalMs", 0.0);

        assertEquals(0L, config.getAnimation().getResendIntervalMs());
    }

    @Test
    void defaultConfigExposesVigourAndGlideBalanceValues() {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        assertTrue(config.getVigour().isEnabled());
        assertEquals(6.0, config.getVigour().getMaxCharges(), 0.00001);
        assertEquals(1.0, config.getVigour().getUpwardFlapCost(), 0.00001);
        assertEquals(1.0, config.getVigour().getForwardBoostCost(), 0.00001);
        assertEquals(4.0, config.getVigour().getGroundedRechargeSecondsPerCharge(), 0.00001);
        assertEquals(8.0, config.getVigour().getFastFlightRechargeSecondsPerCharge(), 0.00001);
        assertEquals(0.80, config.getVigour().getFastFlightRechargeSpeedRatio(), 0.00001);
        assertEquals(0.75, config.getVigour().getRechargeDelayAfterSpendSeconds(), 0.00001);
        assertTrue(config.getVigour().isHudEnabled());
        assertEquals(100L, config.getVigour().getHudResendIntervalMs());
        assertEquals(15.0, config.getMovement().getMaxGlideSpeed(), 0.00001);
        assertEquals(6.0, config.getMovement().getNeutralGlideSpeed(), 0.00001);
        assertEquals(4.0, config.getMovement().getNeutralGlideAcceleration(), 0.00001);
        assertEquals(0.15, config.getMovement().getNeutralGlideDeceleration(), 0.00001);
        assertEquals(1.5, config.getMovement().getGlideStartKickSpeed(), 0.00001);
        assertEquals(1.0, config.getMovement().getGlideSinkSpeed(), 0.00001);
        assertEquals(2.0, config.getMovement().getGlideSinkAcceleration(), 0.00001);
        assertEquals(8.0, config.getMovement().getStallSpeedThreshold(), 0.00001);
        assertEquals(5.0, config.getMovement().getStallSinkSpeed(), 0.00001);
        assertEquals(3.0, config.getMovement().getPitchDownSpeedGain(), 0.00001);
    }

    @Test
    void defaultConfigExposesCurveAndLaunchValues() {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        assertEquals(1.6, config.getCurve().getDiveLoadRampSeconds(), 0.00001);
        assertEquals(0.6, config.getCurve().getDiveLoadDecaySeconds(), 0.00001);
        assertEquals(1.55, config.getCurve().getDivePitchExponent(), 0.00001);
        assertEquals(1.1, config.getCurve().getClimbLoadRampSeconds(), 0.00001);
        assertEquals(0.6, config.getCurve().getClimbLoadDecaySeconds(), 0.00001);
        assertEquals(1.35, config.getCurve().getClimbPitchExponent(), 0.00001);
        assertEquals(0.5, config.getCurve().getClimbSpeedEligibilityExponent(), 0.00001);
        assertEquals(2.0, config.getCurve().getBoostedSpeedDecay(), 0.00001);
        assertTrue(config.getBoost().isDirectional());
        assertEquals(0.45, config.getBoost().getUpwardPitchLiftMultiplier(), 0.00001);
        assertEquals(3.0, config.getBoost().getUpwardPitchLiftCap(), 0.00001);
        assertTrue(config.getLaunch().isEnabled());
        assertEquals(AvatarFlightLaunchSettings.INPUT_CROUCH_HOLD, config.getLaunch().getPreferredInput());
        assertEquals(AvatarFlightLaunchSettings.INPUT_CROUCH_HOLD, config.getLaunch().getFallbackInput());
        assertEquals(500L, config.getLaunch().getMinChargeMs());
        assertEquals(3000L, config.getLaunch().getMaxChargeMs());
        assertEquals(0.65, config.getLaunch().getChargeExponent(), 0.00001);
        assertEquals(6.0, config.getLaunch().getMinUpImpulse(), 0.00001);
        assertEquals(18.0, config.getLaunch().getMaxUpImpulse(), 0.00001);
        assertEquals(6.0, config.getLaunch().getMinForwardImpulse(), 0.00001);
        assertEquals(11.0, config.getLaunch().getMaxForwardImpulse(), 0.00001);
        assertEquals(1.0, config.getLaunch().getPartialChargeCost(), 0.00001);
        assertEquals(2.0, config.getLaunch().getFullChargeCost(), 0.00001);
        assertEquals(0.6, config.getLaunch().getFullChargeCostThreshold(), 0.00001);
        assertTrue(config.getVfx().isEnabled());
        assertTrue(config.getVfx().isForwardBoostEnabled());
        assertEquals(AvatarFlightVfxSettings.DEFAULT_FORWARD_BOOST_SYSTEM,
                config.getVfx().getForwardBoostParticleSystem());
        assertEquals(1.0, config.getVfx().getForwardBoostScale(), 0.00001);
        assertTrue(config.getVfx().isUpwardBoostEnabled());
        assertEquals(AvatarFlightVfxSettings.DEFAULT_UPWARD_BOOST_SYSTEM,
                config.getVfx().getUpwardBoostParticleSystem());
        assertEquals(1.0, config.getVfx().getUpwardBoostScale(), 0.00001);
        assertEquals(AvatarFlightVfxSettings.DEFAULT_CHARGE_SYSTEM,
                config.getVfx().getLaunchChargeParticleSystem());
        assertEquals(600L, config.getVfx().getLaunchChargeEarlyIntervalMs());
        assertEquals(150L, config.getVfx().getLaunchChargeFullIntervalMs());
        assertEquals(0.65, config.getVfx().getLaunchChargeMinScale(), 0.00001);
        assertEquals(1.15, config.getVfx().getLaunchChargeMaxScale(), 0.00001);
        assertEquals(0.70, config.getVfx().getLaunchCancelScale(), 0.00001);
        assertEquals(0.75, config.getVfx().getLaunchReleasePartialScale(), 0.00001);
        assertEquals(1.0, config.getVfx().getLaunchReleaseMidScale(), 0.00001);
        assertEquals(1.20, config.getVfx().getLaunchReleaseFullScale(), 0.00001);
        assertEquals(0.45, config.getVfx().getLaunchReleaseMidThreshold(), 0.00001);
        assertEquals(0.80, config.getVfx().getLaunchReleaseFullThreshold(), 0.00001);
        assertTrue(config.getAudio().isEnabled());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_CHARGE_SOUND,
                config.getAudio().getLaunchChargeSoundEvent());
        assertEquals(600L, config.getAudio().getLaunchChargeEarlyIntervalMs());
        assertEquals(180L, config.getAudio().getLaunchChargeFullIntervalMs());
        assertEquals(0.32, config.getAudio().getLaunchChargeMinVolume(), 0.00001);
        assertEquals(0.90, config.getAudio().getLaunchChargeMaxVolume(), 0.00001);
        assertEquals(0.85, config.getAudio().getLaunchChargeMinPitch(), 0.00001);
        assertEquals(1.12, config.getAudio().getLaunchChargeMaxPitch(), 0.00001);
        assertEquals(AvatarFlightAudioSettings.DEFAULT_FULL_SOUND,
                config.getAudio().getLaunchReleaseFullSoundEvent());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_UPWARD_FLAP_SOUND,
                config.getAudio().getUpwardFlapSoundEvent());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_FORWARD_BOOST_SOUND,
                config.getAudio().getForwardBoostSoundEvent());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_AIRBRAKE_SOUND,
                config.getAudio().getAirbrakeSoundEvent());
    }

    @Test
    void omittedTopLevelSectionsInheritFromParent() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "movement", "maxForwardSpeed", 22.0);
        setNestedField(parent, "jump", "upwardImpulse", 9.0);
        setNestedField(parent, "vfx", "launchChargeEarlyIntervalMs", 725.0);
        setNestedField(parent, "audio", "launchChargeEarlyIntervalMs", 710.0);
        setNestedField(parent, "abilityAnimation", "upwardBoostAnimation", "ParentFlap");
        setNestedField(parent, "abilityAnimation", "slot", "Movement");

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertEquals(22.0, child.getMovement().getMaxForwardSpeed(), 0.00001);
        assertEquals(9.0, child.getJump().getUpwardImpulse(), 0.00001);
        assertEquals(725L, child.getVfx().getLaunchChargeEarlyIntervalMs());
        assertEquals(710L, child.getAudio().getLaunchChargeEarlyIntervalMs());
        assertEquals("ParentFlap", child.getAbilityAnimation().getUpwardBoostAnimation());
        assertEquals("Movement", child.getAbilityAnimation().getSlot());
    }

    @Test
    void explicitAbilityAnimationSectionInheritsMissingNestedKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "abilityAnimation", "upwardBoostAnimation", "ParentFlap");
        setNestedField(parent, "abilityAnimation", "forwardBoostAnimation", "ParentBoost");
        setNestedField(parent, "abilityAnimation", "airbrakeAnimation", "ParentBrake");
        setNestedField(parent, "abilityAnimation", "airbrakeDurationMs", 900.0);
        setNestedField(parent, "abilityAnimation", "slot", "Movement");
        setNestedField(child, "abilityAnimation", "forwardBoostAnimation", "ChildBoost");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("AbilityAnimation"),
                Map.of("AbilityAnimation", Set.of("ForwardBoostAnimation"))
        );

        assertEquals("ParentFlap", child.getAbilityAnimation().getUpwardBoostAnimation());
        assertEquals("ChildBoost", child.getAbilityAnimation().getForwardBoostAnimation());
        assertEquals("ParentBrake", child.getAbilityAnimation().getAirbrakeAnimation());
        assertEquals(900L, child.getAbilityAnimation().getAirbrakeDurationMs());
        assertEquals("Movement", child.getAbilityAnimation().getSlot());
    }

    @Test
    void explicitBlankAbilityAnimationDisablesOnlyThatCue() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "abilityAnimation", "upwardBoostAnimation", "ParentFlap");
        setNestedField(parent, "abilityAnimation", "forwardBoostAnimation", "ParentBoost");
        setNestedField(child, "abilityAnimation", "upwardBoostAnimation", "");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("AbilityAnimation"),
                Map.of("AbilityAnimation", Set.of("UpwardBoostAnimation"))
        );

        assertEquals("", child.getAbilityAnimation().getUpwardBoostAnimation());
        assertEquals("ParentBoost", child.getAbilityAnimation().getForwardBoostAnimation());
    }

    @Test
    void explicitAudioSectionInheritsMissingNestedKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "audio", "launchChargeEarlyIntervalMs", 800.0);
        setNestedField(parent, "audio", "launchReleaseFullSoundEvent", "ParentFull");
        setNestedField(parent, "audio", "forwardBoostSoundEvent", "ParentBoostSound");
        setNestedField(child, "audio", "launchChargeEarlyIntervalMs", 300.0);
        setNestedField(child, "audio", "launchReleaseFullSoundEvent", "ChildFull");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Audio"),
                Map.of("Audio", Set.of("LaunchChargeEarlyIntervalMs"))
        );

        assertEquals(300L, child.getAudio().getLaunchChargeEarlyIntervalMs());
        assertEquals("ParentFull", child.getAudio().getLaunchReleaseFullSoundEvent());
        assertEquals("ParentBoostSound", child.getAudio().getForwardBoostSoundEvent());
    }

    @Test
    void explicitBlankAudioCueDisablesOnlyThatCue() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(child, "audio", "launchReadySoundEvent", "");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Audio"),
                Map.of("Audio", Set.of("LaunchReadySoundEvent"))
        );

        assertEquals("", child.getAudio().getLaunchReadySoundEvent());
        assertEquals(AvatarFlightAudioSettings.DEFAULT_CANCEL_SOUND,
                child.getAudio().getLaunchCancelSoundEvent());
    }

    @Test
    void explicitVfxSectionInheritsMissingNestedKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "vfx", "launchChargeEarlyIntervalMs", 800.0);
        setNestedField(parent, "vfx", "launchReleaseFullParticleSystem", "ParentFull");
        setNestedField(parent, "vfx", "forwardBoostParticleSystem", "ParentForwardBoost");
        setNestedField(parent, "vfx", "forwardBoostScale", 4.0);
        setNestedField(child, "vfx", "launchChargeEarlyIntervalMs", 300.0);
        setNestedField(child, "vfx", "launchReleaseFullParticleSystem", "ChildFull");

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Vfx"),
                Map.of("Vfx", Set.of("LaunchChargeEarlyIntervalMs"))
        );

        assertEquals(300L, child.getVfx().getLaunchChargeEarlyIntervalMs());
        assertEquals("ParentFull", child.getVfx().getLaunchReleaseFullParticleSystem());
        assertEquals("ParentForwardBoost", child.getVfx().getForwardBoostParticleSystem());
        assertEquals(4.0, child.getVfx().getForwardBoostScale(), 0.00001);
    }

    @Test
    void boostVfxScaleDefaultsToExistingSpeciesLaunchScale() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setNestedField(config, "vfx", "launchReleaseMidScale", 6.0);

        assertEquals(6.0, config.getVfx().getForwardBoostScale(), 0.00001);
        assertEquals(6.0, config.getVfx().getUpwardBoostScale(), 0.00001);

        setNestedField(config, "vfx", "forwardBoostScale", 4.5);
        assertEquals(4.5, config.getVfx().getForwardBoostScale(), 0.00001);
        assertEquals(6.0, config.getVfx().getUpwardBoostScale(), 0.00001);
    }

    @Test
    void invalidVfxTimingScaleAndThresholdValuesClampSafely() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setNestedField(config, "vfx", "launchChargeEarlyIntervalMs", 100.0);
        setNestedField(config, "vfx", "launchChargeFullIntervalMs", 900.0);
        setNestedField(config, "vfx", "launchChargeMinScale", -1.0);
        setNestedField(config, "vfx", "upwardBoostScale", -1.0);
        setNestedField(config, "vfx", "launchReleaseMidThreshold", 0.9);
        setNestedField(config, "vfx", "launchReleaseFullThreshold", 0.2);

        assertEquals(100L, config.getVfx().getLaunchChargeEarlyIntervalMs());
        assertEquals(100L, config.getVfx().getLaunchChargeFullIntervalMs());
        assertEquals(0.65, config.getVfx().getLaunchChargeMinScale(), 0.00001);
        assertEquals(1.0, config.getVfx().getUpwardBoostScale(), 0.00001);
        assertEquals(0.9, config.getVfx().getLaunchReleaseMidThreshold(), 0.00001);
        assertEquals(0.9, config.getVfx().getLaunchReleaseFullThreshold(), 0.00001);
    }

    @Test
    void explicitNestedMovementSectionOnlyKeepsExplicitKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "movement", "maxForwardSpeed", 20.0);
        setNestedField(parent, "movement", "maxGlideSpeed", 23.0);
        setNestedField(parent, "movement", "neutralGlideSpeed", 9.0);
        setNestedField(parent, "movement", "neutralGlideAcceleration", 7.0);
        setNestedField(parent, "movement", "neutralGlideDeceleration", 3.0);
        setNestedField(parent, "movement", "glideStartKickSpeed", 2.5);
        setNestedField(parent, "movement", "forwardAcceleration", 40.0);
        setNestedField(parent, "movement", "stallSpeedThreshold", 11.0);
        setNestedField(parent, "movement", "stallSinkSpeed", 6.0);
        setNestedField(child, "movement", "maxForwardSpeed", 12.0);
        setNestedField(child, "movement", "maxGlideSpeed", 13.0);
        setNestedField(child, "movement", "neutralGlideSpeed", 4.0);
        setNestedField(child, "movement", "neutralGlideAcceleration", 2.0);
        setNestedField(child, "movement", "neutralGlideDeceleration", 1.0);
        setNestedField(child, "movement", "glideStartKickSpeed", 0.5);
        setNestedField(child, "movement", "forwardAcceleration", 5.0);
        setNestedField(child, "movement", "stallSpeedThreshold", 5.0);
        setNestedField(child, "movement", "stallSinkSpeed", 4.0);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Movement"),
                Map.of("Movement", Set.of("MaxForwardSpeed"))
        );

        assertEquals(12.0, child.getMovement().getMaxForwardSpeed(), 0.00001);
        assertEquals(23.0, child.getMovement().getMaxGlideSpeed(), 0.00001);
        assertEquals(9.0, child.getMovement().getNeutralGlideSpeed(), 0.00001);
        assertEquals(7.0, child.getMovement().getNeutralGlideAcceleration(), 0.00001);
        assertEquals(3.0, child.getMovement().getNeutralGlideDeceleration(), 0.00001);
        assertEquals(2.5, child.getMovement().getGlideStartKickSpeed(), 0.00001);
        assertEquals(40.0, child.getMovement().getForwardAcceleration(), 0.00001);
        assertEquals(11.0, child.getMovement().getStallSpeedThreshold(), 0.00001);
        assertEquals(6.0, child.getMovement().getStallSinkSpeed(), 0.00001);
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
    void explicitInputSectionInheritsMissingAirborneJumpDelay() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "input", "intentTimeoutMs", 900.0);
        setNestedField(parent, "input", "airborneJumpActivationDelayMs", 400.0);
        setNestedField(child, "input", "intentTimeoutMs", 300.0);
        setNestedField(child, "input", "airborneJumpActivationDelayMs", 50.0);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Input"),
                Map.of("Input", Set.of("IntentTimeoutMs"))
        );

        assertEquals(300L, child.getInput().getIntentTimeoutMs());
        assertEquals(400L, child.getInput().getAirborneJumpActivationDelayMs());
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
        setNestedField(parent, "boost", "directional", false);
        setNestedField(parent, "boost", "upwardPitchLiftMultiplier", 0.25);
        setNestedField(parent, "boost", "upwardPitchLiftCap", 1.5);
        setNestedField(child, "boost", "forwardImpulse", 5.0);
        setNestedField(child, "boost", "cooldownSeconds", 0.5);
        setNestedField(child, "boost", "durationSeconds", 0.2);
        setNestedField(child, "boost", "directional", true);
        setNestedField(child, "boost", "upwardPitchLiftMultiplier", 0.5);
        setNestedField(child, "boost", "upwardPitchLiftCap", 2.5);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Boost"),
                Map.of("Boost", Set.of("ForwardImpulse"))
        );

        assertEquals(5.0, child.getBoost().getForwardImpulse(), 0.00001);
        assertEquals(2.0, child.getBoost().getCooldownSeconds(), 0.00001);
        assertEquals(0.8, child.getBoost().getDurationSeconds(), 0.00001);
        assertFalse(child.getBoost().isDirectional());
        assertEquals(0.25, child.getBoost().getUpwardPitchLiftMultiplier(), 0.00001);
        assertEquals(1.5, child.getBoost().getUpwardPitchLiftCap(), 0.00001);
    }

    @Test
    void explicitCurveAndLaunchSectionsInheritMissingNestedKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "curve", "diveLoadRampSeconds", 2.4);
        setNestedField(parent, "curve", "climbPitchExponent", 1.8);
        setNestedField(child, "curve", "diveLoadRampSeconds", 0.9);
        setNestedField(child, "curve", "climbPitchExponent", 1.1);
        setNestedField(parent, "launch", "maxUpImpulse", 22.0);
        setNestedField(parent, "launch", "partialChargeCost", 0.5);
        setNestedField(child, "launch", "maxUpImpulse", 12.0);
        setNestedField(child, "launch", "partialChargeCost", 3.0);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Curve", "Launch"),
                Map.of(
                        "Curve", Set.of("DiveLoadRampSeconds"),
                        "Launch", Set.of("MaxUpImpulse")
                )
        );

        assertEquals(0.9, child.getCurve().getDiveLoadRampSeconds(), 0.00001);
        assertEquals(1.8, child.getCurve().getClimbPitchExponent(), 0.00001);
        assertEquals(12.0, child.getLaunch().getMaxUpImpulse(), 0.00001);
        assertEquals(0.5, child.getLaunch().getPartialChargeCost(), 0.00001);
    }

    @Test
    void explicitVigourSectionInheritsMissingNestedKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "vigour", "maxCharges", 8.0);
        setNestedField(parent, "vigour", "fastFlightRechargeSecondsPerCharge", 10.0);
        setNestedField(parent, "vigour", "hudEnabled", false);
        setNestedField(child, "vigour", "maxCharges", 4.0);
        setNestedField(child, "vigour", "fastFlightRechargeSecondsPerCharge", 3.0);
        setNestedField(child, "vigour", "hudEnabled", true);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Vigour"),
                Map.of("Vigour", Set.of("MaxCharges"))
        );

        assertEquals(4.0, child.getVigour().getMaxCharges(), 0.00001);
        assertEquals(10.0, child.getVigour().getFastFlightRechargeSecondsPerCharge(), 0.00001);
        assertFalse(child.getVigour().isHudEnabled());
    }

    @Test
    void explicitMovementSectionInheritsMissingGlideSinkKeys() throws Exception {
        TwAvatarFlightConfig parent = TwAvatarFlightConfig.defaultConfig();
        TwAvatarFlightConfig child = TwAvatarFlightConfig.defaultConfig();
        setNestedField(parent, "movement", "glideSinkSpeed", 1.4);
        setNestedField(parent, "movement", "glideSinkAcceleration", 3.0);
        setNestedField(child, "movement", "glideSinkSpeed", 0.2);
        setNestedField(child, "movement", "glideSinkAcceleration", 0.3);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Movement"),
                Map.of("Movement", Set.of("GlideSinkSpeed"))
        );

        assertEquals(0.2, child.getMovement().getGlideSinkSpeed(), 0.00001);
        assertEquals(3.0, child.getMovement().getGlideSinkAcceleration(), 0.00001);
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
        setNestedField(parent, "animation", "pitchUpBankLeftPoseAnimation", "ParentPitchUpBankLeft");
        setNestedField(parent, "animation", "pitchUpBankRightPoseAnimation", "ParentPitchUpBankRight");
        setNestedField(parent, "animation", "pitchDownBankLeftPoseAnimation", "ParentPitchDownBankLeft");
        setNestedField(parent, "animation", "pitchDownBankRightPoseAnimation", "ParentPitchDownBankRight");
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
        setNestedField(child, "animation", "pitchUpBankLeftPoseAnimation", "ChildPitchUpBankLeft");
        setNestedField(child, "animation", "pitchUpBankRightPoseAnimation", "ChildPitchUpBankRight");
        setNestedField(child, "animation", "pitchDownBankLeftPoseAnimation", "ChildPitchDownBankLeft");
        setNestedField(child, "animation", "pitchDownBankRightPoseAnimation", "ChildPitchDownBankRight");
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
        assertEquals("ParentPitchUpBankLeft", child.getAnimation().getPitchUpBankLeftPoseAnimation());
        assertEquals("ParentPitchUpBankRight", child.getAnimation().getPitchUpBankRightPoseAnimation());
        assertEquals("ParentPitchDownBankLeft", child.getAnimation().getPitchDownBankLeftPoseAnimation());
        assertEquals("ParentPitchDownBankRight", child.getAnimation().getPitchDownBankRightPoseAnimation());
        assertEquals(12.0, child.getAnimation().getPitchPoseThresholdDegrees(), 0.00001);
        assertEquals(9.0, child.getAnimation().getRollPoseThresholdDegrees(), 0.00001);
        assertEquals(333L, child.getAnimation().getPoseResendIntervalMs());
        assertEquals("ChildPitchUp", child.getAnimation().pitchPoseAnimationFor(80.0));
        assertEquals("ParentBankLeft", child.getAnimation().rollPoseAnimationFor(-20.0));
        assertEquals("ParentPitchUpBankLeft", child.getAnimation().sharedPoseAnimationFor(80.0, -20.0));
        assertEquals("ParentBankRight", child.getAnimation().sharedPoseAnimationFor(0.0, 20.0));
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

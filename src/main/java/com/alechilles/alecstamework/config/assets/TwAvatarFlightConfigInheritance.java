package com.alechilles.alecstamework.config.assets;

import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies top-level and one-level nested parent fallback for avatar-flight configs.
 */
final class TwAvatarFlightConfigInheritance {

    private TwAvatarFlightConfigInheritance() {
    }

    static void inheritMissingFrom(@Nonnull TwAvatarFlightConfig target,
                                   @Nonnull TwAvatarFlightConfig parent,
                                   @Nonnull Set<String> top,
                                   @Nullable Map<String, Set<String>> nestedByTop) {
        if (!top.contains("Enabled")) target.enabled = parent.enabled;
        if (!top.contains("Priority")) target.priority = parent.priority;
        inheritModel(target, parent, nested(nestedByTop, "Model"), top);
        inheritInput(target, parent, nested(nestedByTop, "Input"), top);
        inheritMovement(target, parent, nested(nestedByTop, "Movement"), top);
        inheritCurve(target, parent, nested(nestedByTop, "Curve"), top);
        inheritJump(target, parent, nested(nestedByTop, "Jump"), top);
        inheritBoost(target, parent, nested(nestedByTop, "Boost"), top);
        inheritLaunch(target, parent, nested(nestedByTop, "Launch"), top);
        inheritVfx(target, parent, nested(nestedByTop, "Vfx"), top);
        inheritAudio(target, parent, nested(nestedByTop, "Audio"), top);
        inheritTrails(target, parent, nested(nestedByTop, "Trails"), top);
        inheritVigour(target, parent, nested(nestedByTop, "Vigour"), top);
        inheritAnimation(target, parent, nested(nestedByTop, "Animation"), top);
        inheritAbilityAnimation(target, parent, nested(nestedByTop, "AbilityAnimation"), top);
        inheritRiderVisual(target, parent, nested(nestedByTop, "RiderVisual"), top);
        inheritMounting(target, parent, nested(nestedByTop, "Mounting"), top);
        inheritDebug(target, parent, nested(nestedByTop, "Debug"), top);
    }

    private static void inheritModel(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                     @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Model")) target.model = parent.model;
        else if (keys != null && target.model != null && parent.model != null) {
            if (!keys.contains("ApplyModel")) target.model.applyModel = parent.model.applyModel;
            if (!keys.contains("ModelId")) target.model.modelId = parent.model.modelId;
            if (!keys.contains("Scale")) target.model.scale = parent.model.scale;
            if (!keys.contains("CameraPositionOffset")) {
                target.model.cameraPositionOffset = parent.model.cameraPositionOffset;
            }
            if (!keys.contains("EyeHeight")) target.model.eyeHeight = parent.model.eyeHeight;
        }
    }

    private static void inheritInput(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                     @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Input")) target.input = parent.input;
        else if (keys != null && target.input != null && parent.input != null) {
            if (!keys.contains("IntentTimeoutMs")) target.input.intentTimeoutMs = parent.input.intentTimeoutMs;
            if (!keys.contains("ForwardDeadzone")) target.input.forwardDeadzone = parent.input.forwardDeadzone;
            if (!keys.contains("StrafeDeadzone")) target.input.strafeDeadzone = parent.input.strafeDeadzone;
            if (!keys.contains("AirborneJumpActivationDelayMs")) {
                target.input.airborneJumpActivationDelayMs = parent.input.airborneJumpActivationDelayMs;
            }
        }
    }

    private static void inheritMovement(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                        @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Movement")) target.movement = parent.movement;
        else if (keys != null && target.movement != null && parent.movement != null) {
            if (!keys.contains("GroundedMoveSpeed")) target.movement.groundedMoveSpeed = parent.movement.groundedMoveSpeed;
            if (!keys.contains("MaxForwardSpeed")) target.movement.maxForwardSpeed = parent.movement.maxForwardSpeed;
            if (!keys.contains("MaxGlideSpeed")) target.movement.maxGlideSpeed = parent.movement.maxGlideSpeed;
            if (!keys.contains("NeutralGlideSpeed")) target.movement.neutralGlideSpeed = parent.movement.neutralGlideSpeed;
            if (!keys.contains("NeutralGlideAcceleration")) target.movement.neutralGlideAcceleration = parent.movement.neutralGlideAcceleration;
            if (!keys.contains("NeutralGlideDeceleration")) target.movement.neutralGlideDeceleration = parent.movement.neutralGlideDeceleration;
            if (!keys.contains("GlideStartKickSpeed")) target.movement.glideStartKickSpeed = parent.movement.glideStartKickSpeed;
            if (!keys.contains("ForwardAcceleration")) target.movement.forwardAcceleration = parent.movement.forwardAcceleration;
            if (!keys.contains("MaxBackwardSpeed")) target.movement.maxBackwardSpeed = parent.movement.maxBackwardSpeed;
            if (!keys.contains("BackwardAcceleration")) target.movement.backwardAcceleration = parent.movement.backwardAcceleration;
            if (!keys.contains("AirbrakeDeceleration")) target.movement.airbrakeDeceleration = parent.movement.airbrakeDeceleration;
            if (!keys.contains("HoverHorizontalDamping")) target.movement.hoverHorizontalDamping = parent.movement.hoverHorizontalDamping;
            if (!keys.contains("HoverVerticalDamping")) target.movement.hoverVerticalDamping = parent.movement.hoverVerticalDamping;
            if (!keys.contains("GlideSinkSpeed")) target.movement.glideSinkSpeed = parent.movement.glideSinkSpeed;
            if (!keys.contains("GlideSinkAcceleration")) target.movement.glideSinkAcceleration = parent.movement.glideSinkAcceleration;
            if (!keys.contains("StallSpeedThreshold")) target.movement.stallSpeedThreshold = parent.movement.stallSpeedThreshold;
            if (!keys.contains("StallSinkSpeed")) target.movement.stallSinkSpeed = parent.movement.stallSinkSpeed;
            if (!keys.contains("DescendSpeed")) target.movement.descendSpeed = parent.movement.descendSpeed;
            if (!keys.contains("MaxFallSpeed")) target.movement.maxFallSpeed = parent.movement.maxFallSpeed;
            if (!keys.contains("PitchUpLiftScale")) target.movement.pitchUpLiftScale = parent.movement.pitchUpLiftScale;
            if (!keys.contains("PitchUpSpeedCost")) target.movement.pitchUpSpeedCost = parent.movement.pitchUpSpeedCost;
            if (!keys.contains("PitchDownDiveScale")) target.movement.pitchDownDiveScale = parent.movement.pitchDownDiveScale;
            if (!keys.contains("PitchDownSpeedGain")) target.movement.pitchDownSpeedGain = parent.movement.pitchDownSpeedGain;
        }
    }

    private static void inheritCurve(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                     @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Curve")) target.curve = parent.curve;
        else if (keys != null && parent.curve != null) {
            if (target.curve == null) target.curve = parent.curve;
            else target.curve.inheritMissingFrom(parent.curve, keys);
        }
    }

    private static void inheritJump(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                    @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Jump")) target.jump = parent.jump;
        else if (keys != null && target.jump != null && parent.jump != null) {
            if (!keys.contains("UpwardImpulse")) target.jump.upwardImpulse = parent.jump.upwardImpulse;
            if (!keys.contains("CooldownSeconds")) target.jump.cooldownSeconds = parent.jump.cooldownSeconds;
        }
    }

    private static void inheritBoost(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                     @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Boost")) target.boost = parent.boost;
        else if (keys != null && target.boost != null && parent.boost != null) {
            if (!keys.contains("ForwardImpulse")) target.boost.forwardImpulse = parent.boost.forwardImpulse;
            if (!keys.contains("CooldownSeconds")) target.boost.cooldownSeconds = parent.boost.cooldownSeconds;
            if (!keys.contains("DurationSeconds")) target.boost.durationSeconds = parent.boost.durationSeconds;
            if (!keys.contains("Directional")) target.boost.directional = parent.boost.directional;
            if (!keys.contains("UpwardPitchLiftMultiplier")) target.boost.upwardPitchLiftMultiplier = parent.boost.upwardPitchLiftMultiplier;
            if (!keys.contains("UpwardPitchLiftCap")) target.boost.upwardPitchLiftCap = parent.boost.upwardPitchLiftCap;
        }
    }

    private static void inheritLaunch(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                      @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Launch")) target.launch = parent.launch;
        else if (keys != null && parent.launch != null) {
            if (target.launch == null) target.launch = parent.launch;
            else target.launch.inheritMissingFrom(parent.launch, keys);
        }
    }

    private static void inheritVfx(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                   @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Vfx")) target.vfx = parent.vfx;
        else if (keys != null && parent.vfx != null) {
            if (target.vfx == null) target.vfx = parent.vfx;
            else target.vfx.inheritMissingFrom(parent.vfx, keys);
        }
    }

    private static void inheritAudio(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                     @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Audio")) target.audio = parent.audio;
        else if (keys != null && parent.audio != null) {
            if (target.audio == null) target.audio = parent.audio;
            else target.audio.inheritMissingFrom(parent.audio, keys);
        }
    }

    private static void inheritTrails(TwAvatarFlightConfig target,
                                      TwAvatarFlightConfig parent,
                                      @Nullable Set<String> keys,
                                      Set<String> top) {
        if (!top.contains("Trails")) target.trails = parent.trails;
        else if (keys != null && parent.trails != null) {
            if (target.trails == null) target.trails = parent.trails;
            else target.trails.inheritMissingFrom(parent.trails, keys);
        }
    }

    private static void inheritVigour(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                      @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Vigour")) target.vigour = parent.vigour;
        else if (keys != null && target.vigour != null && parent.vigour != null) {
            if (!keys.contains("Enabled")) target.vigour.enabled = parent.vigour.enabled;
            if (!keys.contains("MaxCharges")) target.vigour.maxCharges = parent.vigour.maxCharges;
            if (!keys.contains("UpwardFlapCost")) target.vigour.upwardFlapCost = parent.vigour.upwardFlapCost;
            if (!keys.contains("ForwardBoostCost")) target.vigour.forwardBoostCost = parent.vigour.forwardBoostCost;
            if (!keys.contains("GroundedRechargeSecondsPerCharge")) target.vigour.groundedRechargeSecondsPerCharge = parent.vigour.groundedRechargeSecondsPerCharge;
            if (!keys.contains("FastFlightRechargeSecondsPerCharge")) target.vigour.fastFlightRechargeSecondsPerCharge = parent.vigour.fastFlightRechargeSecondsPerCharge;
            if (!keys.contains("FastFlightRechargeSpeedRatio")) target.vigour.fastFlightRechargeSpeedRatio = parent.vigour.fastFlightRechargeSpeedRatio;
            if (!keys.contains("RechargeDelayAfterSpendSeconds")) target.vigour.rechargeDelayAfterSpendSeconds = parent.vigour.rechargeDelayAfterSpendSeconds;
            if (!keys.contains("HudEnabled")) target.vigour.hudEnabled = parent.vigour.hudEnabled;
            if (!keys.contains("HudResendIntervalMs")) target.vigour.hudResendIntervalMs = parent.vigour.hudResendIntervalMs;
        }
    }

    private static void inheritAnimation(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                         @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Animation")) target.animation = parent.animation;
        else if (keys != null && target.animation != null && parent.animation != null) {
            TwAvatarFlightConfig.AnimationSettings child = target.animation;
            TwAvatarFlightConfig.AnimationSettings inherited = parent.animation;
            if (!keys.contains("IdleAnimation")) child.idleAnimation = inherited.idleAnimation;
            if (!keys.contains("FlightAnimation")) child.flightAnimation = inherited.flightAnimation;
            if (!keys.contains("FastFlightAnimation")) child.fastFlightAnimation = inherited.fastFlightAnimation;
            if (!keys.contains("ResendIntervalMs")) child.resendIntervalMs = inherited.resendIntervalMs;
            if (!keys.contains("SuppressNonMovementAnimations")) child.suppressNonMovementAnimations = inherited.suppressNonMovementAnimations;
            if (!keys.contains("SuppressActionAnimation")) child.suppressActionAnimation = inherited.suppressActionAnimation;
            if (!keys.contains("SuppressStatusAnimation")) child.suppressStatusAnimation = inherited.suppressStatusAnimation;
            if (!keys.contains("SuppressEmoteAnimation")) child.suppressEmoteAnimation = inherited.suppressEmoteAnimation;
            if (!keys.contains("SuppressFaceAnimation")) child.suppressFaceAnimation = inherited.suppressFaceAnimation;
            if (!keys.contains("SuppressionIntervalMs")) child.suppressionIntervalMs = inherited.suppressionIntervalMs;
            if (!keys.contains("PoseAnimationsEnabled")) child.poseAnimationsEnabled = inherited.poseAnimationsEnabled;
            if (!keys.contains("PitchPoseSlot")) child.pitchPoseSlot = inherited.pitchPoseSlot;
            if (!keys.contains("RollPoseSlot")) child.rollPoseSlot = inherited.rollPoseSlot;
            if (!keys.contains("PitchUpPoseAnimation")) child.pitchUpPoseAnimation = inherited.pitchUpPoseAnimation;
            if (!keys.contains("PitchDownPoseAnimation")) child.pitchDownPoseAnimation = inherited.pitchDownPoseAnimation;
            if (!keys.contains("BankLeftPoseAnimation")) child.bankLeftPoseAnimation = inherited.bankLeftPoseAnimation;
            if (!keys.contains("BankRightPoseAnimation")) child.bankRightPoseAnimation = inherited.bankRightPoseAnimation;
            if (!keys.contains("PitchUpBankLeftPoseAnimation")) child.pitchUpBankLeftPoseAnimation = inherited.pitchUpBankLeftPoseAnimation;
            if (!keys.contains("PitchUpBankRightPoseAnimation")) child.pitchUpBankRightPoseAnimation = inherited.pitchUpBankRightPoseAnimation;
            if (!keys.contains("PitchDownBankLeftPoseAnimation")) child.pitchDownBankLeftPoseAnimation = inherited.pitchDownBankLeftPoseAnimation;
            if (!keys.contains("PitchDownBankRightPoseAnimation")) child.pitchDownBankRightPoseAnimation = inherited.pitchDownBankRightPoseAnimation;
            if (!keys.contains("PitchPoseThresholdDegrees")) child.pitchPoseThresholdDegrees = inherited.pitchPoseThresholdDegrees;
            if (!keys.contains("RollPoseThresholdDegrees")) child.rollPoseThresholdDegrees = inherited.rollPoseThresholdDegrees;
            if (!keys.contains("PoseResendIntervalMs")) child.poseResendIntervalMs = inherited.poseResendIntervalMs;
        }
    }

    private static void inheritRiderVisual(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                           @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("RiderVisual")) target.riderVisual = parent.riderVisual;
        else if (keys != null && target.riderVisual != null && parent.riderVisual != null) {
            if (!keys.contains("HideOwnerEquipment")) target.riderVisual.hideOwnerEquipment = parent.riderVisual.hideOwnerEquipment;
            if (!keys.contains("HideOwnerArmor")) target.riderVisual.hideOwnerArmor = parent.riderVisual.hideOwnerArmor;
            if (!keys.contains("HideOwnerHands")) target.riderVisual.hideOwnerHands = parent.riderVisual.hideOwnerHands;
            if (!keys.contains("ShowRider")) target.riderVisual.showRider = parent.riderVisual.showRider;
            if (!keys.contains("IncludeAppearanceAttachments")) {
                target.riderVisual.includeAppearanceAttachments = parent.riderVisual.includeAppearanceAttachments;
            }
            if (!keys.contains("SeatOffsetX")) target.riderVisual.seatOffsetX = parent.riderVisual.seatOffsetX;
            if (!keys.contains("SeatOffsetY")) target.riderVisual.seatOffsetY = parent.riderVisual.seatOffsetY;
            if (!keys.contains("SeatOffsetZ")) target.riderVisual.seatOffsetZ = parent.riderVisual.seatOffsetZ;
            if (!keys.contains("EquipmentResendIntervalMs")) target.riderVisual.equipmentResendIntervalMs = parent.riderVisual.equipmentResendIntervalMs;
        }
    }

    private static void inheritAbilityAnimation(TwAvatarFlightConfig target,
                                                TwAvatarFlightConfig parent,
                                                @Nullable Set<String> keys,
                                                Set<String> top) {
        if (!top.contains("AbilityAnimation")) target.abilityAnimation = parent.abilityAnimation;
        else if (keys != null && parent.abilityAnimation != null) {
            if (target.abilityAnimation == null) target.abilityAnimation = parent.abilityAnimation;
            else target.abilityAnimation.inheritMissingFrom(parent.abilityAnimation, keys);
        }
    }

    private static void inheritMounting(TwAvatarFlightConfig target,
                                        TwAvatarFlightConfig parent,
                                        @Nullable Set<String> keys,
                                        Set<String> top) {
        if (!top.contains("Mounting")) target.mounting = parent.mounting;
        else if (keys != null && parent.mounting != null) {
            if (target.mounting == null) target.mounting = parent.mounting;
            else target.mounting.inheritMissingFrom(parent.mounting, keys);
        }
    }

    private static void inheritDebug(TwAvatarFlightConfig target, TwAvatarFlightConfig parent,
                                     @Nullable Set<String> keys, Set<String> top) {
        if (!top.contains("Debug")) target.debug = parent.debug;
        else if (keys != null && target.debug != null && parent.debug != null) {
            if (!keys.contains("LogControllerTicks")) target.debug.logControllerTicks = parent.debug.logControllerTicks;
            if (!keys.contains("LogInputTransitions")) target.debug.logInputTransitions = parent.debug.logInputTransitions;
        }
    }

    @Nullable
    private static Set<String> nested(@Nullable Map<String, Set<String>> nestedByTop,
                                      @Nonnull String topLevelKey) {
        return nestedByTop == null ? null : nestedByTop.get(topLevelKey);
    }
}

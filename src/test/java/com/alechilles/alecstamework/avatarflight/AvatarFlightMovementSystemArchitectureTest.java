package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightMovementSystemArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightMovementSystem.java"
    );
    private static final Path ANIMATION_SOURCE = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "avatarflight",
            "AvatarFlightAnimationService.java"
    );

    @Test
    void controllerStateComesFromAvatarFlightComponent() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("AvatarFlightController.State.from(flight)"),
                "avatar flight must use its stored controller velocity, not physics reconciliation velocity");
        assertFalse(source.contains("currentVelocity == null ? flight.getVelocityX()"),
                "feeding live physics velocity back into the controller caused glide velocity oscillation");
    }

    @Test
    void movementSystemPersistsHudTelemetryBeforeHudSystemRuns() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("flight.setHudPitchRadians(output.visualPitchRadians())"),
                "pitch readout should come from the same controller output that drives visual pitch");
        assertTrue(source.contains("flight.setHudTargetSpeedRatio(output.hudTargetSpeedRatio())"),
                "speed target marker should be persisted for AvatarFlightHudSystem, which runs after movement");
    }

    @Test
    void movementSystemDrivesLaunchAudioFromAuthoritativeLaunchState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("private final AvatarFlightLaunchAudioService launchAudioService"),
                "launch audio should have a focused service instead of expanding controller responsibilities");
        assertTrue(source.contains("launchAudioService.tick("),
                "launch audio must consume the same authoritative charge/release state as launch VFX");
        assertTrue(source.contains("TransformComponent transform = commandBuffer.getComponent(ref, transformType)"),
                "launch VFX and audio should share one current world-space transform lookup");
    }

    @Test
    void freshPacketInputOwnsGroundedState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("boolean onGround = stale ? states == null || states.onGround : input.isOnGround()"),
                "fresh packet-captured grounded state must win, but stale cached grounded state must not latch");
    }

    @Test
    void visualPoseWritesPitchAndRollThroughCommandBuffer() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("applyVisualPose(ref, commandBuffer, controllerInput, output)"),
                "avatar flight must update the transformed model pose while custom flight velocity is active");
        assertTrue(source.contains("if (transform != null && transform.getRotation() != null)"),
                "missing transform rotation must not block the HeadRotation pose write");
        assertTrue(source.contains("transform.getRotation().setPitch((float) output.visualPitchRadians())"));
        assertTrue(source.contains("transform.getRotation().setRoll((float) output.visualRollRadians())"));
        assertTrue(source.contains("commandBuffer.putComponent(ref, transformType, transform)"),
                "runtime systems must write component mutations through the command buffer");
        assertTrue(source.contains("headRotation.getRotation().setPitch((float) output.visualPitchRadians())"),
                "visible player-model pitch may follow HeadRotation rather than Transform rotation");
        assertTrue(source.contains("headRotation.getRotation().setRoll((float) output.visualRollRadians())"));
        assertTrue(source.contains("commandBuffer.putComponent(ref, headRotationType, headRotation)"));
    }

    @Test
    void visualPoseAndPoseAnimationsAreGuardedByVelocityApplication() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String animationSource = Files.readString(ANIMATION_SOURCE, StandardCharsets.UTF_8);
        String tick = methodSlice(source, "public void tick");

        int velocityGuardIndex = tick.indexOf("if (applyingVelocity)");
        int poseIndex = tick.indexOf("applyVisualPose(ref, commandBuffer, controllerInput, output)");
        String groundedBranch = substringBetween(
                tick,
                "} else if (hasFlightVisualOverrides) {",
                "commandBuffer.putComponent(ref, flightType, flight);"
        );

        assertTrue(poseIndex >= 0, "avatar flight must write visual pose while custom velocity is active");
        assertTrue(tick.contains("animationService.tick("),
                "avatar flight must delegate pose animations while custom velocity is active");
        assertTrue(velocityGuardIndex >= 0, "test expects the velocity guard to remain present");
        assertTrue(velocityGuardIndex < poseIndex,
                "grounded transformed mode should not keep rewriting transform pitch/roll");
        assertTrue(animationSource.indexOf("if (!applyingVelocity)")
                        < animationSource.indexOf("applyPoseAnimations(ref, commandBuffer, flight, config, output, now)"),
                "grounded transformed mode should clear rather than replay flight pose animations");
        assertFalse(groundedBranch.contains("applyVisualPose("),
                "grounded transformed mode should leave native grounded visuals alone after cleanup");
        assertFalse(groundedBranch.contains("animationService.tick("),
                "the animation service should run once before the grounded cleanup branch");
    }

    @Test
    void groundedAvatarFlightDoesNotSuppressNativeLocomotionAnimations() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String animationSource = Files.readString(ANIMATION_SOURCE, StandardCharsets.UTF_8);
        String tick = methodSlice(source, "public void tick");

        assertFalse(tick.contains("suppressPlayerOverlayAnimations(ref, commandBuffer, flight, config);"),
                "unconditional slot suppression prevents native grounded sprint/step animations while transformed");
        assertTrue(tick.contains("boolean suppressingOverlays ="),
                "the suppression decision should be named so diagnostics can report it");
        assertTrue(tick.contains("animationService.tick("),
                "overlay suppression should be delegated with the custom flight visual state");
        assertTrue(animationSource.contains("if (!applyingVelocity && !hasFlightVisualOverrides)"),
                "grounded transformed locomotion should be able to use the same native animation path as a plain model swap");
    }

    @Test
    void ownerClientFlyingStateIsSyncedThroughPlayerApplyMovementStates() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("Player.applyMovementStates"),
                "self-client animation state needs the same saved flying-state packet creative flight uses");
        assertTrue(source.contains("new SavedMovementStates(desiredFlying)"),
                "avatar flight should sync true while flying and false when returning to grounded");
        assertTrue(source.contains("flight.isClientFlyingSynced() != desiredFlying"),
                "the self-client saved flight packet should only be sent when the desired flying state changes");
    }

    @Test
    void normalAvatarFlightDoesNotBorrowNativeClientFlightCapability() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("syncActivationCapability"),
                "normal avatar flight must not enable native canFly for jump/double-jump activation");
        assertFalse(source.contains("AvatarFlightActivationCapability"),
                "normal avatar flight should keep client-flight capability isolated to the standalone debug probe");
        assertTrue(source.contains("syncOwnerClientFlyingState(ref, commandBuffer, flight, applyingVelocity)"),
                "custom flight may still sync saved flying state for transformed-player animations");
    }

    @Test
    void avatarFlightForcesFlyingMovementStateInsteadOfFallState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("applyFlightMovementState(ref, commandBuffer, output)"),
                "avatar flight must override player movement states while applying custom air velocity");
        assertTrue(source.contains("states.flying = true"));
        assertTrue(source.contains("states.falling = false"));
        assertTrue(source.contains("states.fallingFar = false"));
        assertTrue(source.contains("states.horizontalIdle = output.horizontalIdle()"));
        assertTrue(source.contains("states.sprinting = false"),
                "fast-flight animation is explicitly driven, so avatar flight should not leave native sprint bits latched");
        assertFalse(source.contains("states.sprinting = states.sprinting || output.fastFlight()"),
                "preserving a stale native sprint bit keeps the transformed model stuck in sprint/fast-flight animation");
        assertFalse(source.contains("states.sprinting = output.fastFlight()"),
                "movement-state sprint should not duplicate the explicit fast-flight Movement animation");
    }

    @Test
    void movementStateSprintDoesNotDriveBoostIntent() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String inputConversion = methodSlice(source, "private AvatarFlightController.Input toControllerInput");

        assertFalse(source.contains("boolean liveSprint = states != null && states.sprinting"),
                "avatar flight writes sprinting for fast-flight animation, so reading it back as boost input repeats boosts on cooldown");
        assertFalse(inputConversion.contains("input.isSprinting()"),
                "held sprint state must not be treated as a level-triggered boost intent");
        assertTrue(source.contains("input.consumeSprintBoost("),
                "packet sprint should be reduced to a one-shot rising-edge boost event");
    }

    @Test
    void groundedAvatarFlightLeavesNativeMovementStateOwner() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String tick = methodSlice(source, "public void tick");
        String groundedBranch = substringBetween(
                tick,
                "} else if (hasFlightVisualOverrides) {",
                "commandBuffer.putComponent(ref, flightType, flight);"
        );

        assertFalse(source.contains("applyGroundedSprintMovementState"),
                "grounded transformed mode should not rewrite native sprint/movement animation state");
        assertFalse(source.contains("states.sprinting = input.isSprinting()"),
                "packet sprint input should be observed, not written back to MovementStates by avatar flight");
        assertFalse(groundedBranch.contains("states.sprinting = false"),
                "grounded transformed sprint must not be blindly suppressed when the player is actually sprinting");
        assertFalse(groundedBranch.contains("states.sprinting = input.isSprinting()"),
                "grounded transformed mode should leave current sprint ownership with native PlayerInput processing");
    }

    @Test
    void groundedTransitionOnlyCleansFlightVisualOverrides() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String animationSource = Files.readString(ANIMATION_SOURCE, StandardCharsets.UTF_8);
        String tick = methodSlice(source, "public void tick");

        assertTrue(tick.contains("flight.isClientFlyingSynced() || animationService.hasOverrides(flight)"),
                "grounded cleanup should be driven by avatar-flight-owned visual state");
        assertTrue(tick.contains("animationService.tick("),
                "leaving custom flight should clear forced animation overrides through their owner");
        assertTrue(tick.contains("clearFlightMovementState(ref, commandBuffer, controllerInput)"),
                "forced airborne MovementStates must be cleared once before native grounded locomotion resumes");
        assertTrue(animationSource.contains("clearMovementAnimation(ref, commandBuffer, flight)"),
                "forced Movement slot animation must be stopped when returning to native grounded mode");
        assertTrue(animationSource.contains("clearPoseAnimations(ref, commandBuffer, flight, config)"),
                "pitch and bank pose slots should be stopped when returning to native grounded mode");
        assertTrue(tick.contains("resetVisualPose(ref, commandBuffer)"),
                "transform/head pitch and roll should be reset once on the transition out of custom flight");
    }

    @Test
    void flightMovementCleanupClearsOnlyTameworkOwnedAirborneBits() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String cleanup = methodSlice(source, "private void clearFlightMovementState");

        assertTrue(cleanup.contains("states.flying = false"),
                "landing should clear Tamework's forced airborne state");
        assertTrue(cleanup.contains("states.sprinting = false"),
                "fast-flight movement-state sprint must not remain latched after custom flight stops");
        assertTrue(cleanup.contains("states.running = false"));
        assertTrue(cleanup.contains("states.walking = false"));
        assertTrue(cleanup.contains("states.jumping = false"));
        assertTrue(cleanup.contains("states.falling = false"));
        assertTrue(cleanup.contains("states.fallingFar = false"));
        assertTrue(cleanup.contains("states.climbing = false"));
        assertTrue(cleanup.contains("states.mantling = false"));
        assertTrue(cleanup.contains("states.sliding = false"));
        assertTrue(cleanup.contains("states.gliding = false"));
        assertTrue(cleanup.contains("states.idle = true"));
        assertTrue(cleanup.contains("states.horizontalIdle = true"));
        assertTrue(cleanup.contains("states.onGround = input.onGround()"),
                "cleanup should not claim grounded when the controller input still says airborne");
        assertTrue(cleanup.contains("commandBuffer.putComponent(ref, movementStatesType, component)"),
                "runtime systems must write cleaned movement-state components through the command buffer");
        assertFalse(cleanup.contains("input.isSprinting()"),
                "transition cleanup should not mirror live grounded sprint state");
    }

    @Test
    void inactiveAvatarFlightStartsOnlyFromReinsActions() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("boolean activeFlight = flight.getMode() != AvatarFlightMode.GROUNDED"),
                "movement input conversion must know whether the controller is already in avatar-flight mode");
        assertTrue(source.contains("boolean itemFlightStart = reinsFlap || reinsBoost"),
                "inactive avatar flight should start from explicit Flightmaster's Reins actions");
        assertFalse(source.contains("input.consumeAirborneJumpPress("),
                "jump/double-jump input must not enter avatar flight");
        assertTrue(source.contains("? reinsFlap || (!stale && input.isJumping())"),
                "held jump should continue to repeat flaps only after avatar flight is already active");
        assertTrue(source.contains(": reinsFlap"),
                "inactive avatar flight may start from the Reins flap action");
        assertTrue(source.contains("boolean boostIntent = reinsBoost || (activeFlight && sprintBoost)"),
                "inactive avatar flight may start from Q boost, but not from unreliable sprint pulses");
        assertTrue(source.contains("onGround && !itemFlightStart"),
                "item actions should be allowed to lift off even while the cached movement state is grounded");
    }

    @Test
    void reinsBoostActionCanDriveBoostIntentWithoutSprintDetection() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("input.consumeReinsBoost("),
                "Flightmaster's Reins Q action should feed the existing boost intent path");
        assertTrue(source.contains("boolean boostIntent = reinsBoost || (activeFlight && sprintBoost)"),
                "Q boost must not depend on unreliable airborne sprint detection when entering flight");
    }

    @Test
    void avatarFlightExplicitlyDrivesTransformedPlayerMovementAnimation() throws Exception {
        String movementSource = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String source = Files.readString(ANIMATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(movementSource.contains("animationService.tick("),
                "the movement orchestrator should delegate transformed-player animation ownership");
        assertTrue(source.contains("AnimationUtils.playAnimation(ref, AnimationSlot.Movement"),
                "avatar flight must use the generic entity animation packet, not NPC-only helpers");
        assertTrue(source.contains("config.getAnimation().animationFor(output.horizontalIdle(), output.fastFlight())"),
                "animation names should stay config-driven instead of hardcoded in the system");
        assertTrue(source.contains("flight.setMovementAnimationId(animationId)"),
                "repeated movement animation packets should be gated by tracked avatar-flight state");
        assertTrue(movementSource.contains("hasGroundedMovementIntent(controllerInput, config)"),
                "grounded movement input should release the temporary landing-idle override");
        assertTrue(source.contains("if (isGroundedIdleHandoffActive(flight))"),
                "landing idle should remain tracked until grounded movement begins");
        assertTrue(source.contains("flight.setMovementAnimationId(GROUNDED_IDLE_ANIMATION)"),
                "the landing-idle packet must remain owned so it can be explicitly stopped");
        assertTrue(source.contains("maintainGroundedIdle(ref, commandBuffer, flight, config, now)"),
                "stationary grounded idle should be refreshed like other transformed movement animations");
        assertTrue(source.contains("now + config.getAnimation().getResendIntervalMs()"),
                "grounded idle refreshes must use the configured packet throttle");
    }

    @Test
    void avatarFlightSuppressesPlayerOverlayAnimationSlotsWhileActive() throws Exception {
        String source = Files.readString(ANIMATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("suppressPlayerOverlayAnimations(ref, commandBuffer, flight, config, suppressingOverlays, now)"),
                "transformed-player flight should suppress held-item/combat overlay slots only while custom flight visuals are active");
        assertTrue(source.contains("config.getAnimation().isSuppressNonMovementAnimations()"),
                "suppression should be config-driven for unsafe model-swap experiments");
        assertTrue(source.contains("!isPoseSlot(AnimationSlot.Status, pitchSlot, rollSlot)"),
                "configured pose-animation slots must not be erased by overlay suppression");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Action, true, commandBuffer)"),
                "Action slot is where item/combat animations can be applied to the wrong transformed model");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, commandBuffer)"),
                "Status slot should be cleared so held-item/status overlays do not persist on the transformed model");
        assertTrue(source.contains("AnimationUtils.stopAnimation(ref, AnimationSlot.Emote, true, commandBuffer)"),
                "Emote slot should be cleared by default because player emotes are authored for the player rig");
        assertTrue(source.contains("flight.setNextSuppressedAnimationAtMs(now + animation.getSuppressionIntervalMs())"),
                "slot suppression should be throttled instead of spamming packets every tick");
    }

    @Test
    void avatarFlightCanDriveOwnerSafePoseAnimationSlots() throws Exception {
        String source = Files.readString(ANIMATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("applyPoseAnimations(ref, commandBuffer, flight, config, output, now)"),
                "owner-visible pitch/bank must use animation packets instead of self TransformUpdate correction");
        assertTrue(source.contains("AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor("),
                "pitch pose selection should use the standard/catalog-driven breakpoint grid");
        assertTrue(source.contains("AvatarFlightPoseAnimationCatalog.rollPoseAnimationFor("),
                "roll pose selection should use the standard/catalog-driven breakpoint grid");
        assertTrue(source.contains("if (pitchSlot == rollSlot)"),
                "same-slot pose configs must drive one combined pose animation instead of two competing packets");
        assertTrue(source.contains("AvatarFlightPoseAnimationCatalog.sharedPoseAnimationFor("),
                "shared pose slots should preserve pitch and bank through a single selected animation");
        assertTrue(source.contains("AnimationUtils.playAnimation(ref, slot, animationId, true, commandBuffer)"),
                "pose animations must be sent to the owner client through the safe animation channel");
        assertTrue(source.contains("AnimationSlot.VALUES"),
                "pose animation slots should be parsed from config without hardcoding only one slot");
    }

    @Test
    void acceptedAbilitiesUseProtectedConfiguredSlotAnimations() throws Exception {
        String movementSource = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String source = Files.readString(ANIMATION_SOURCE, StandardCharsets.UTF_8);

        assertFalse(movementSource.contains("AnimationUtils."),
                "the movement orchestrator should not regain direct animation ownership");
        assertTrue(source.contains("AvatarFlightAbilityAnimationSelector.select(output, settings)"),
                "ability animation cues must come from accepted controller outputs");
        assertTrue(source.contains("model.getAnimationSetMap().containsKey(cue.animationId())"),
                "ability animations bypass engine validation and must be checked against the transformed model");
        assertTrue(source.contains("AnimationSlot slot = resolveAnimationSlot(settings.getSlot(), AnimationSlot.Action)"),
                "ability cues must resolve their configured slot with a compatible Action default");
        assertTrue(source.contains("AnimationUtils.playAnimation(ref, slot, cue.animationId(), true"),
                "ability cues must play through their configured owner-visible slot");
        assertTrue(source.contains("!doesAbilityOwnSlot(flight, AnimationSlot.Action, now)"),
                "held-item suppression must yield while a one-shot ability cue owns the Action slot");
        assertTrue(source.contains("doesAbilityOwnSlot(flight, AnimationSlot.Movement, now)"),
                "normal movement clips must yield while a full-body ability cue owns Movement");
        assertTrue(source.contains("warnedMissingAnimations.add(warningKey)"),
                "missing configured clips should warn once instead of failing or spamming logs");
    }

    @Test
    void landingHandsMovementAnimationDirectlyToGroundedIdle() throws Exception {
        String source = Files.readString(ANIMATION_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("boolean hadAnimationOverrides = hasOverrides(flight)"),
                "landing must retain pre-expiry animation ownership for a cue ending on the touchdown tick");
        assertTrue(source.contains("needsGroundedIdleHandoff(output.mode(), hadAnimationOverrides)"),
                "landing handoff must only run when a flight animation override was owned entering the tick");
        assertTrue(source.contains("model.getAnimationSetMap().containsKey(GROUNDED_IDLE_ANIMATION)"),
                "the transformed model must expose Idle before the landing handoff plays it");
        assertTrue(source.contains("AnimationSlot.Movement, GROUNDED_IDLE_ANIMATION, true"),
                "landing must explicitly enter Idle instead of leaving Movement stopped");
        assertTrue(source.contains("flight.setMovementAnimationId(\"\")"),
                "the idle handoff must release Tamework animation ownership for normal locomotion");
    }

    @Test
    void debugLogFormatsMovementStateFlags() throws Exception {
        String source = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "avatarflight",
                "AvatarFlightDebugLogService.java"
        ), StandardCharsets.UTF_8);

        assertTrue(source.contains("formatMovementStates(states)"),
                "debug logs should print movement-state flags, not MovementStates object identities");
        assertFalse(source.contains("states.toString()"),
                "object identity logs do not help diagnose client animation state");
    }

    @Test
    void debugLogReportsVisualOwnershipAndRawInputState() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String debugSource = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "avatarflight",
                "AvatarFlightDebugLogService.java"
        ), StandardCharsets.UTF_8);

        assertTrue(source.contains("AvatarFlightDebugLogService"),
                "controller diagnostics should live outside the already-large movement system");
        assertTrue(source.contains("debugLogService.maybeLogControllerTick("),
                "movement system should delegate controller tick diagnostics");
        assertTrue(debugSource.contains("visualOverride=%s"),
                "diagnostics must say whether avatar flight still owns visual overrides");
        assertTrue(debugSource.contains("suppressOverlays=%s"),
                "diagnostics must say whether overlay suppression is active this tick");
        assertTrue(debugSource.contains("clientFly=%s"),
                "diagnostics must include the synced self-client flying state");
        assertTrue(debugSource.contains("movementAnim=%s"),
                "diagnostics must include forced Movement slot animation ownership");
        assertTrue(debugSource.contains("poseAnim=%s/%s"),
                "diagnostics must include pitch and roll pose animation ownership");
        assertTrue(debugSource.contains("rawInput=sprint=%s"),
                "diagnostics must include raw packet sprint before controller conversion");
        assertTrue(debugSource.contains("rawStale=%s"),
                "diagnostics must include whether raw packet input is stale");
        assertTrue(debugSource.contains("rawAgeMs=%s"),
                "diagnostics must include raw input age for stale-input investigations");
    }

    @Test
    void avatarFlightRunsAfterBaseMovementStatesBeforeAnimationTracking() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("Order.AFTER, MovementStatesSystems.TickingSystem.class"),
                "avatar flight animation states must run after base movement-state derivation");
        assertTrue(source.contains("Order.BEFORE, ModelSystems.AnimationEntityTrackerUpdate.class"),
                "avatar flight animation states must be visible before model animation tracking");
    }

    private static String methodSlice(String source, String methodStart) {
        int start = source.indexOf(methodStart);
        if (start < 0) {
            return "";
        }
        int nextMethod = source.indexOf("\n    @", start + methodStart.length());
        if (nextMethod < 0) {
            nextMethod = source.indexOf("\n    private", start + methodStart.length());
        }
        return nextMethod < 0 ? source.substring(start) : source.substring(start, nextMethod);
    }

    private static String substringBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        int end = source.indexOf(endMarker, start + startMarker.length());
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }
}

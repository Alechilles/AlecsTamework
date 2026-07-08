package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightPacketInputCaptureArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightPacketInputCapture.java"
    );

    @Test
    void componentStoreAccessRunsInsideWorldExecuteCallback() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int executeIndex = source.indexOf("world.execute(() -> captureOnWorld");
        int captureOnWorldIndex = source.indexOf("private void captureOnWorld");
        int componentReadIndex = source.indexOf("store.getComponent(ref, flightType)");
        int componentWriteIndex = source.indexOf("store.putComponent(ref, inputType, input)");

        assertTrue(executeIndex > 0, "packet capture must queue world-thread work before component access");
        assertTrue(captureOnWorldIndex > executeIndex, "component access helper should be invoked by world.execute");
        assertTrue(componentReadIndex > captureOnWorldIndex, "component reads must be inside captureOnWorld");
        assertTrue(componentWriteIndex > captureOnWorldIndex, "component writes must be inside captureOnWorld");
    }

    @Test
    void stateLessPacketsCanUseMovementStatesComponentFallback() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("MovementStatesComponent"),
                "avatar packet capture must inspect movement states when packets omit states");
        assertTrue(source.contains("resolveMovementFallback(ref, store)"),
                "state fallback must feed the same projection path as packet movement states");
        assertTrue(source.contains("packetStates != null"),
                "packet-provided movement states should still take priority over component fallback");
    }

    @Test
    void momentaryButtonsComeOnlyFromPacketStates() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("MovementStates packetStates = resolvePacketMovementStates(packet);"),
                "capture must keep packet-provided states separate from component fallback states");
        assertTrue(source.contains("boolean stateObserved = packetStates != null"),
                "capture must distinguish packet-observed button state from MovementStatesComponent fallback");
        assertTrue(source.contains("boolean jumpHeld = stateObserved && (packetStates.jumping || packetStates.swimJumping)"),
                "jump must not be latched from MovementStatesComponent fallback");
        assertTrue(source.contains("config.getInput().getAirborneJumpActivationDelayMs()"),
                "fresh airborne jump activation should be gated by the configured post-takeoff delay");
        assertTrue(source.contains("input.updateJumping(!suppressLaunchJump && jumpHeld, now, grounded,"),
                "grounded launch charge should suppress raw jump only while preserving packet-state-only jump capture");
        assertTrue(source.contains("boolean crouchHeld = stateObserved && (packetStates.crouching || packetStates.forcedCrouching)"),
                "crouch must not be latched from MovementStatesComponent fallback");
        assertTrue(source.contains("input.setCrouching(!suppressLaunchCrouch && crouchHeld)"),
                "grounded launch charge should suppress raw crouch only while preserving packet-state-only crouch capture");
        assertTrue(source.contains("if (stateObserved) {"),
                "stateless movement packets must preserve held/released button state until an explicit state packet arrives");
    }

    @Test
    void boostSprintEdgesComeOnlyFromPacketStates() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("input.updateSprinting(packetStates.sprinting, now)"),
                "sprint boost must use packet sprint edges only; movement fallback includes our own fast-flight animation sprint state");
        assertFalse(source.contains("input.setSprinting(movementStates != null && movementStates.sprinting)"),
                "feeding MovementStatesComponent sprint back into boost intent repeats boosts on cooldown");
    }

    @Test
    void statelessPacketsDoNotResetAirborneJumpActivationClock() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("boolean grounded = stateObserved ? packetStates.onGround : input.isOnGround()"),
                "packets without movement states must preserve the last observed grounded state");
        assertTrue(source.contains("if (stateObserved) {\r\n            input.setOnGround(grounded, now);\r\n        }")
                        || source.contains("if (stateObserved) {\n            input.setOnGround(grounded, now);\n        }"),
                "MovementStatesComponent fallback must not reset airborneSinceMs between jump edge packets");
        assertFalse(source.contains("boolean grounded = movementStates == null ? input.isOnGround() : movementStates.onGround"),
                "fallback movement state can be stale relative to packet input and must not drive jump activation timing");
    }

    @Test
    void clientFlyingToggleCanQueueAirborneJumpActivation() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("flight.getMode() == AvatarFlightMode.GROUNDED && packetStates.flying && !grounded"),
                "client canFly double-jump reports flying=true rather than a second raw jump state");
        assertTrue(source.contains("input.queueAirborneJumpPress(now, config.getInput().getAirborneJumpActivationDelayMs())"),
                "creative-style flight toggles must feed the same delayed explicit airborne entry path");
        assertTrue(source.contains("Player.applyMovementStates(ref, new SavedMovementStates(false), movementStates, store)"),
                "native client flying must be cancelled immediately so custom avatar flight owns movement");
    }

    @Test
    void jumpHoldLaunchChargeIsCapturedAndCanBeCancelled() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("beginLaunchCharge"),
                "grounded held jump should begin a charged launch when configured");
        assertTrue(source.contains("if (launchHeld && grounded)"),
                "jump-hold launch charge should only begin from the ground");
        assertTrue(source.contains("} else if (launchStateObserved && !launchHeld && input.isLaunchCharging())"),
                "explicit release after charge should queue launch even if native movement left the ground during the hold");
        assertTrue(source.contains("boolean suppressLaunchJump = jumpHoldLaunchInput"),
                "raw jump suppression should be tied to active launch charge/release state");
        assertTrue(source.contains("queueLaunchRelease"),
                "releasing jump after charge should queue a one-shot launch release");
        assertTrue(source.contains("cancelLaunchCharge"),
                "leaving the configured launch state should cancel an in-progress charge");
    }

    @Test
    void crouchHoldLaunchChargeIsCapturedWithoutFeedingDescentIntent() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("boolean crouchHeld = stateObserved && (packetStates.crouching || packetStates.forcedCrouching)"),
                "crouch-hold launch must read live packet crouch state, not latched movement fallback state");
        assertTrue(source.contains("boolean crouchHoldLaunchInput = config.getLaunch().isEnabled() && usesCrouchHoldLaunch(config)"),
                "crouch-hold launch should be independently configurable from jump-hold launch");
        assertTrue(source.contains("boolean launchHeld = (jumpHoldLaunchInput && jumpHeld) || (crouchHoldLaunchInput && crouchHeld)"),
                "launch hold state should combine configured packet launch inputs without treating unconfigured crouch as launch");
        assertTrue(source.contains("handleLaunchCharge(input, now, jumpHoldLaunchInput || crouchHoldLaunchInput, launchHeld, grounded"),
                "grounded crouch hold should feed the same one-shot launch release path");
        assertTrue(source.contains("boolean suppressLaunchCrouch = crouchHoldLaunchInput"),
                "grounded crouch launch charge must suppress raw crouch descent while the launch is charging");
        assertTrue(source.contains("input.setCrouching(!suppressLaunchCrouch && crouchHeld)"),
                "crouch should resume normal downward movement when it is not being used for grounded launch charge");
    }

    @Test
    void statelessPacketsDoNotReleaseHeldLaunchCharge() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("boolean stateObserved = packetStates != null"),
                "movement packets without button state must not count as launch button release");
        int handleCallIndex = source.indexOf(
                "handleLaunchCharge(input, now, jumpHoldLaunchInput || crouchHoldLaunchInput, launchHeld, grounded");
        int observedArgumentIndex = source.indexOf("stateObserved);", handleCallIndex);
        assertTrue(handleCallIndex > 0 && observedArgumentIndex > handleCallIndex,
                "launch charge handling must know whether this packet actually observed jump/crouch state");
        assertTrue(source.contains("} else if (launchStateObserved && !launchHeld && input.isLaunchCharging())"),
                "only explicit button-release packets should queue a launch release");
    }

    @Test
    void packetVelocityDoesNotBecomeVerticalIntent() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("input.setVerticalAxis(0.0)"),
                "avatar-flight vertical controls must come from jump/crouch states and item actions");
        assertFalse(source.contains("resolveVerticalAxis(packet)"),
                "server-applied vertical velocity must not feed back as climb/descend intent");
        assertFalse(source.contains("CLIENT_FLIGHT_VERTICAL_INTENT_THRESHOLD"),
                "thresholding packet velocity still latches server-applied vertical movement");
    }

    @Test
    void explicitWishMovementCanRestartFromForcedHorizontalIdle() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int wishIndex = source.indexOf("MovementIntentProjector.AxisProjection explicitWish = intent.wish()");
        int wishReturnIndex = source.indexOf("return explicitWish;");
        int horizontalIdleIndex = source.indexOf("return states.horizontalIdle ? MovementIntentProjector.AxisProjection.idle()");

        assertTrue(wishIndex > 0, "packet wish movement must be inspected directly");
        assertTrue(wishReturnIndex > wishIndex, "explicit W/S/A/D wish movement must win before fallback projection");
        assertTrue(horizontalIdleIndex > wishReturnIndex,
                "forced hover animation state must not suppress explicit movement intent");
    }

    @Test
    void stoppedFlightCanRestartFromPacketDeltaWithoutRefeedingActiveGlideVelocity() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int stoppedIndex = source.indexOf("isStoredFlightHorizontallyStopped(flight) && primary.hasUsableIntent()");
        int horizontalIdleIndex = source.indexOf("return states.horizontalIdle ? MovementIntentProjector.AxisProjection.idle() : primary");

        assertTrue(source.contains("resolvePrimaryProjection(playerUuid, flight, movementStates"),
                "packet projection must know the stored custom flight velocity");
        assertTrue(stoppedIndex > 0,
                "W restart after full airbrake should use packet deltas only while custom flight is stopped");
        assertTrue(horizontalIdleIndex > stoppedIndex,
                "stopped-flight restart must run before forced horizontal-idle fallback");
        assertTrue(source.contains("Math.hypot(flight.getVelocityX(), flight.getVelocityZ()) <= STOPPED_RESTART_SPEED_LIMIT"),
                "active glide velocity must not feed back as fresh W/S/A/D intent");
    }

    @Test
    void passiveFallbackCannotCreateBackwardIntentAfterSharpTurn() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("suppressPassiveBackwardFallback(primary, intent)"),
                "2026-07-04 flight log showed projected passive glide velocity becoming persistent BACKING after a sharp turn");
        assertTrue(source.contains("primary.forward() < -PASSIVE_BACKWARD_FALLBACK_DEADZONE"),
                "fallback velocity/position projection may keep positive glide steering but must not synthesize S/back input");
        assertTrue(source.contains("return MovementIntentProjector.AxisProjection.idle()"),
                "suppressed passive backward fallback should neutralize input instead of entering BACKING mode");
    }
}

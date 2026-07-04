package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightControllerTest {
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.defaultConfig();

    @Test
    void groundedWithoutJumpDoesNotApplyVelocity() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(3.0, 0.0, 0.0, 0L, 0L),
                input(0.0, false, false, false, true, 0.0)
        );

        assertEquals(AvatarFlightMode.GROUNDED, output.mode());
        assertFalse(output.applyVelocity());
    }

    @Test
    void idleAirborneHoversInsteadOfGlidingForward() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(6.0, -2.0, 0.0, 0L, 0L),
                input(0.0, false, false, false, false, 0.0)
        );

        assertEquals(AvatarFlightMode.HOVER, output.mode());
        assertTrue(Math.abs(output.velocityX()) < 6.0);
        assertTrue(Math.abs(output.velocityY()) < 2.0);
    }

    @Test
    void forwardInputStartsForwardFlight() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(1.0, false, false, false, false, 0.0)
        );

        assertEquals(AvatarFlightMode.FORWARD_FLIGHT, output.mode());
        assertTrue(output.velocityZ() < 0.0);
        assertTrue(output.applyVelocity());
    }

    @Test
    void backwardInputBrakesForwardVelocity() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -6.0, 0L, 0L),
                input(-1.0, false, false, false, false, 0.0)
        );

        assertEquals(AvatarFlightMode.BRAKING, output.mode());
        assertTrue(Math.abs(output.velocityZ()) < 6.0);
    }

    @Test
    void backwardInputFromRestMovesSlowlyBackward() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(-1.0, false, false, false, false, 0.0)
        );

        assertEquals(AvatarFlightMode.BACKING, output.mode());
        assertTrue(output.velocityZ() > 0.0);
    }

    @Test
    void explicitAirbrakeBrakesForwardVelocityWithoutBackingUp() {
        AvatarFlightController.Output braking = update(
                new AvatarFlightController.State(0.0, 0.0, -6.0, 0L, 0L),
                input(0.0, false, false, false, false, true, 0.0)
        );
        AvatarFlightController.Output stationary = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(0.0, false, false, false, false, true, 0.0)
        );

        assertEquals(AvatarFlightMode.BRAKING, braking.mode());
        assertTrue(Math.abs(braking.velocityZ()) < 6.0);
        assertTrue(stationary.velocityZ() <= 0.0, "right-click airbrake must not become reverse thrust");
    }

    @Test
    void explicitAirbrakeWinsOverForwardInputAndHoversVertically() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, -4.0, -12.0, 0L, 0L),
                input(1.0, false, false, false, false, true, Math.toRadians(-45.0))
        );

        assertEquals(AvatarFlightMode.BRAKING, output.mode());
        assertTrue(Math.abs(output.velocityZ()) < 12.0);
        assertTrue(Math.abs(output.velocityY()) < 4.0);
        assertEquals(-10.2, output.velocityZ(), 0.00001);
        assertEquals(-2.2, output.velocityY(), 0.00001);
    }

    @Test
    void strafeInputAppliesLateralVelocity() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(0.0, 1.0, false, false, false, false, 0.0)
        );

        assertEquals(AvatarFlightMode.HOVER, output.mode());
        assertTrue(output.velocityX() > 0.0);
        assertEquals(0.0, output.velocityZ(), 0.00001);
        assertTrue(output.applyVelocity());
    }

    @Test
    void crouchAppliesConfiguredDescentOnlyWhileHeld() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 1.0, 0.0, 0L, 0L),
                input(0.0, false, true, false, false, 0.0)
        );
        AvatarFlightController.Output released = update(
                new AvatarFlightController.State(output.velocityX(), output.velocityY(), output.velocityZ(), 0L, 0L),
                input(0.0, false, false, false, false, 0.0)
        );

        assertEquals(AvatarFlightMode.DESCENDING, output.mode());
        assertEquals(-CONFIG.getMovement().getDescendSpeed(), output.velocityY(), 0.00001);
        assertTrue(released.velocityY() > output.velocityY(),
                "crouch descent must not remain stuck after the live crouch state clears");
    }

    @Test
    void positiveVerticalIntentAppliesCooldownLimitedJump() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(0.0, 0.0, 1.0, false, false, false, false, 0.0)
        );

        assertTrue(output.jumpApplied());
        assertTrue(output.velocityY() > 0.0);
    }

    @Test
    void negativeVerticalIntentAppliesConfiguredDescent() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 1.0, 0.0, 0L, 0L),
                input(0.0, 0.0, -1.0, false, false, false, false, 0.0)
        );

        assertEquals(AvatarFlightMode.DESCENDING, output.mode());
        assertEquals(-CONFIG.getMovement().getDescendSpeed(), output.velocityY(), 0.00001);
    }

    @Test
    void heldJumpRepeatsOnlyWhenCooldownAllows() {
        AvatarFlightController.Output first = AvatarFlightController.update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(0.0, true, false, false, false, 0.0),
                CONFIG,
                0.1,
                1000L
        );
        AvatarFlightController.Output blocked = AvatarFlightController.update(
                new AvatarFlightController.State(first.velocityX(), first.velocityY(), first.velocityZ(),
                        first.nextJumpAtMs(), 0L),
                input(0.0, true, false, false, false, 0.0),
                CONFIG,
                0.1,
                1100L
        );
        AvatarFlightController.Output repeated = AvatarFlightController.update(
                new AvatarFlightController.State(blocked.velocityX(), blocked.velocityY(), blocked.velocityZ(),
                        blocked.nextJumpAtMs(), 0L),
                input(0.0, true, false, false, false, 0.0),
                CONFIG,
                0.1,
                first.nextJumpAtMs()
        );

        assertTrue(first.jumpApplied());
        assertFalse(blocked.jumpApplied());
        assertTrue(repeated.jumpApplied());
    }

    @Test
    void pitchUpTradesForwardSpeedForAltitude() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L),
                input(1.0, false, false, false, false, Math.toRadians(45.0))
        );

        assertTrue(Math.abs(output.velocityZ()) < 10.0);
        assertTrue(output.velocityY() > 3.0,
                "pitch-up should carve the glide vector upward immediately, not add only a tiny hover-like lift");
    }

    @Test
    void pitchUpRedirectsGlideMomentumIntoClimbWithoutStalling() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -14.0, 0L, 0L),
                input(1.0, false, false, false, false, Math.toRadians(45.0))
        );

        assertTrue(Math.abs(output.velocityZ()) > 8.0,
                "pitch-up should retain forward glide instead of hard-stalling");
        assertTrue(Math.abs(output.velocityZ()) < 13.8,
                "pitch-up should spend some forward speed without dumping it into a stall");
        assertTrue(output.velocityY() > 4.0,
                "pitch-up should create a bird-like climbing arc quickly");
    }

    @Test
    void pitchUpConvertsDiveEnergyIntoSustainedClimbInsteadOfImmediateStall() {
        AvatarFlightController.State state = new AvatarFlightController.State(0.0, -10.0, -14.0, 0L, 0L);
        AvatarFlightController.Output output = null;
        double altitudeChange = 0.0;
        for (int tick = 0; tick < 4; tick++) {
            output = update(state, input(1.0, false, false, false, false, Math.toRadians(45.0)));
            altitudeChange += output.velocityY() * 0.1;
            state = new AvatarFlightController.State(
                    output.velocityX(),
                    output.velocityY(),
                    output.velocityZ(),
                    output.nextJumpAtMs(),
                    output.nextBoostAtMs()
            );
        }

        double horizontalSpeed = Math.sqrt(output.velocityX() * output.velocityX()
                + output.velocityZ() * output.velocityZ());
        assertTrue(horizontalSpeed > 4.5,
                "pulling up from a dive should not hard-stall after only a few ticks");
        assertTrue(output.velocityY() > 3.0,
                "dive speed should keep converting into upward climb during the pull-up");
        assertTrue(altitudeChange > 0.0,
                "a fast dive should buy back noticeable altitude before momentum is spent");
    }

    @Test
    void pitchUpRetainsForwardSpeedWhileConvertingDiveEnergyIntoLift() {
        AvatarFlightController.State state = new AvatarFlightController.State(0.0, -10.0, -14.0, 0L, 0L);
        AvatarFlightController.Output output = null;
        for (int tick = 0; tick < 4; tick++) {
            output = update(state, input(1.0, false, false, false, false, Math.toRadians(45.0)));
            state = new AvatarFlightController.State(
                    output.velocityX(),
                    output.velocityY(),
                    output.velocityZ(),
                    output.nextJumpAtMs(),
                    output.nextBoostAtMs()
            );
        }

        double horizontalSpeed = Math.sqrt(output.velocityX() * output.velocityX()
                + output.velocityZ() * output.velocityZ());
        assertTrue(horizontalSpeed > 9.0,
                "pulling up should keep clear forward flight speed while the dive converts into lift");
        assertTrue(output.velocityY() > 3.0,
                "retaining forward speed must not remove the climb gained from the dive");
    }

    @Test
    void pitchUpTurnPreservesHorizontalMomentumBeforeTradingSpeed() {
        AvatarFlightController.Output output = AvatarFlightController.update(
                new AvatarFlightController.State(10.0, 0.0, 0.0, 0L, 0L),
                input(1.0, false, false, false, false, Math.toRadians(22.0)),
                CONFIG,
                0.1,
                1000L
        );

        assertEquals(AvatarFlightMode.FORWARD_FLIGHT, output.mode());
        assertTrue(output.velocityZ() < -7.0,
                "sharp turns while pitched up should carry existing horizontal speed into the new glide direction");
        assertTrue(output.velocityY() > 0.0);
    }

    @Test
    void pitchUpTradesMomentumForAltitudeWithoutFreshForwardInput() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L),
                input(0.0, false, false, false, false, Math.toRadians(45.0))
        );

        assertTrue(Math.abs(output.velocityZ()) < 9.0);
        assertTrue(output.velocityY() > 0.0);
    }

    @Test
    void heldForwardDoesNotCancelPitchUpSpeedSpend() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L),
                input(1.0, false, false, false, false, Math.toRadians(45.0))
        );

        assertTrue(Math.abs(output.velocityZ()) < 10.0,
                "holding forward must not refill the speed that pitch-up is spending");
        assertTrue(output.velocityY() > 0.0);
    }

    @Test
    void pitchUpCannotGenerateLiftWithoutForwardSpeedToSpend() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(0.0, false, false, false, false, Math.toRadians(45.0))
        );

        assertEquals(AvatarFlightMode.HOVER, output.mode());
        assertEquals(0.0, output.velocityY(), 0.00001);
        assertTrue(output.horizontalIdle());
    }

    @Test
    void pitchDownTradesAltitudeForSpeed() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -4.0, 0L, 0L),
                input(1.0, false, false, false, false, Math.toRadians(-45.0))
        );

        assertTrue(Math.abs(output.velocityZ()) > 4.0);
        assertTrue(output.velocityY() < 0.0);
    }

    @Test
    void pitchDownNeverProducesUpwardLift() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 1.0, -8.0, 0L, 0L),
                input(1.0, false, false, false, false, Math.toRadians(-45.0))
        );

        assertTrue(output.velocityY() < 1.0);
    }

    @Test
    void pitchAndLateralVelocityProduceVisualPose() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(0.0, 1.0, false, false, false, false, Math.toRadians(80.0))
        );

        assertEquals(Math.toRadians(35.0), output.visualPitchRadians(), 0.00001);
        assertTrue(Math.abs(output.visualRollRadians()) > 0.0);
        assertTrue(Math.abs(output.visualRollRadians()) <= Math.toRadians(20.0));
    }

    @Test
    void animationStateUsesHoverFlyAndFastFlightModes() {
        AvatarFlightController.Output hover = update(
                new AvatarFlightController.State(0.0, -0.1, 0.0, 0L, 0L),
                input(0.0, false, false, false, false, 0.0)
        );
        AvatarFlightController.Output fly = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(1.0, false, false, false, false, 0.0)
        );
        AvatarFlightController.Output fast = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(1.0, false, false, true, false, 0.0)
        );

        assertTrue(hover.horizontalIdle());
        assertFalse(hover.fastFlight());
        assertFalse(fly.horizontalIdle());
        assertFalse(fly.fastFlight());
        assertFalse(fast.horizontalIdle());
        assertTrue(fast.fastFlight());
    }

    private static AvatarFlightController.Output update(AvatarFlightController.State state,
                                                        AvatarFlightController.Input input) {
        return AvatarFlightController.update(state, input, CONFIG, 0.1, 1000L);
    }

    private static AvatarFlightController.Input input(double forwardAxis,
                                                      boolean jump,
                                                      boolean crouch,
                                                      boolean sprint,
                                                      boolean onGround,
                                                      double pitchRadians) {
        return input(forwardAxis, jump, crouch, sprint, onGround, false, pitchRadians);
    }

    private static AvatarFlightController.Input input(double forwardAxis,
                                                      boolean jump,
                                                      boolean crouch,
                                                      boolean sprint,
                                                      boolean onGround,
                                                      boolean airbrake,
                                                      double pitchRadians) {
        return input(forwardAxis, 0.0, jump, crouch, sprint, onGround, airbrake, pitchRadians);
    }

    private static AvatarFlightController.Input input(double forwardAxis,
                                                      double strafeAxis,
                                                      boolean jump,
                                                      boolean crouch,
                                                      boolean sprint,
                                                      boolean onGround,
                                                      double pitchRadians) {
        return input(forwardAxis, strafeAxis, jump, crouch, sprint, onGround, false, pitchRadians);
    }

    private static AvatarFlightController.Input input(double forwardAxis,
                                                      double strafeAxis,
                                                      boolean jump,
                                                      boolean crouch,
                                                      boolean sprint,
                                                      boolean onGround,
                                                      boolean airbrake,
                                                      double pitchRadians) {
        return new AvatarFlightController.Input(
                forwardAxis,
                strafeAxis,
                0.0,
                jump,
                crouch,
                sprint,
                airbrake,
                onGround,
                0.0,
                pitchRadians
        );
    }

    private static AvatarFlightController.Input input(double forwardAxis,
                                                      double strafeAxis,
                                                      double verticalAxis,
                                                      boolean jump,
                                                      boolean crouch,
                                                      boolean sprint,
                                                      boolean onGround,
                                                      double pitchRadians) {
        return new AvatarFlightController.Input(
                forwardAxis,
                strafeAxis,
                verticalAxis,
                jump,
                crouch,
                sprint,
                false,
                onGround,
                0.0,
                pitchRadians
        );
    }
}

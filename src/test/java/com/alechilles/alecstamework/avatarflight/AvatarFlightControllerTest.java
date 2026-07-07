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
        assertTrue(Math.hypot(output.velocityX(), output.velocityZ())
                        >= CONFIG.getMovement().getGlideStartKickSpeed(),
                "forward input from hover should seed enough speed to actually start gliding");
        assertTrue(output.applyVelocity());
    }

    @Test
    void pitchDownForwardInputStartsFromHoverWithoutStrafeKick() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(1.0, false, false, false, false, Math.toRadians(-45.0))
        );

        assertEquals(AvatarFlightMode.FORWARD_FLIGHT, output.mode());
        assertTrue(output.velocityZ() < 0.0,
                "looking down while holding forward should start forward glide without needing A/D first");
        assertTrue(output.velocityY() < 0.0);
        assertTrue(Math.hypot(output.velocityX(), output.velocityZ())
                        >= CONFIG.getMovement().getGlideStartKickSpeed(),
                "pitch-down start should still use only the modest configured start kick");
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
    void flapIntentBlockedByResourceGateDoesNotApplyUpwardVelocity() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(0.0, 0.0, true, false, false, false, false, 0.0, false, true)
        );

        assertFalse(output.jumpApplied());
        assertEquals(0.0, output.velocityY(), 0.00001);
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
        assertTrue(Math.abs(output.velocityZ()) < 13.95,
                "pitch-up should spend forward speed gradually instead of dumping it into a stall");
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
    void sharpPitchUpTurnDoesNotCreateExtraHorizontalSpeed() {
        AvatarFlightController.Output output = AvatarFlightController.update(
                new AvatarFlightController.State(10.0, 0.0, 0.0, 0L, 0L),
                input(1.0, false, false, false, false, Math.toRadians(45.0)),
                CONFIG,
                0.1,
                1000L
        );

        double horizontalSpeed = Math.sqrt(output.velocityX() * output.velocityX()
                + output.velocityZ() * output.velocityZ());
        assertTrue(horizontalSpeed <= 10.0,
                "sharp pitch-up turns should redirect existing speed, not create extra horizontal speed");
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
    void neutralForwardFromStallRecoversOnlyModestSpeedAndSinksHarder() {
        AvatarFlightController.State state = new AvatarFlightController.State(0.0, 0.0, -1.0, 0L, 0L);
        AvatarFlightController.Output output = null;
        for (int tick = 0; tick < 10; tick++) {
            output = update(
                    state,
                    input(1.0, false, false, false, false, 0.0)
            );
            state = new AvatarFlightController.State(
                    output.velocityX(),
                    output.velocityY(),
                    output.velocityZ(),
                    output.nextJumpAtMs(),
                    output.nextBoostAtMs()
            );
        }

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(horizontalSpeed <= CONFIG.getMovement().getNeutralGlideSpeed(),
                "level forward glide must not refill full cruise speed after a stall");
        assertTrue(horizontalSpeed < CONFIG.getMovement().getMaxForwardSpeed() * 0.5,
                "stall recovery should remain a modest glide speed unless the player dives or boosts");
        assertTrue(output.velocityY() < -1.5,
                "near-stall glide should sink noticeably instead of hovering in place");
    }

    @Test
    void neutralForwardGlideDecaysHighSpeedInsteadOfHoldingCruise() {
        AvatarFlightController.State state = new AvatarFlightController.State(
                0.0,
                0.0,
                -CONFIG.getMovement().getMaxForwardSpeed(),
                0L,
                0L
        );
        AvatarFlightController.Output output = null;
        for (int tick = 0; tick < 20; tick++) {
            output = update(
                    state,
                    input(1.0, false, false, false, false, 0.0)
            );
            state = new AvatarFlightController.State(
                    output.velocityX(),
                    output.velocityY(),
                    output.velocityZ(),
                    output.nextJumpAtMs(),
                    output.nextBoostAtMs()
            );
        }

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(horizontalSpeed < CONFIG.getMovement().getMaxForwardSpeed() - 3.0,
                "unpowered level glide should bleed speed over time");
        assertTrue(horizontalSpeed > CONFIG.getMovement().getNeutralGlideSpeed(),
                "neutral glide should decay over time instead of snapping to stall speed");
    }

    @Test
    void unpoweredForwardGlideLosesAltitudeOverTime() {
        AvatarFlightController.State state = new AvatarFlightController.State(0.0, 0.0, -14.0, 0L, 0L);
        double altitudeChange = 0.0;
        for (int tick = 0; tick < 200; tick++) {
            AvatarFlightController.Output output = update(
                    state,
                    input(1.0, false, false, false, false, 0.0)
            );
            altitudeChange += output.velocityY() * 0.1;
            state = new AvatarFlightController.State(
                    output.velocityX(),
                    output.velocityY(),
                    output.velocityZ(),
                    output.nextJumpAtMs(),
                    output.nextBoostAtMs()
            );
        }

        assertTrue(altitudeChange < -12.0,
                "unpowered neutral forward glide must eventually lose enough altitude to require landing");
    }

    @Test
    void pitchDownWithoutBoostStaysBelowFastRechargeSpeed() {
        AvatarFlightController.State state = new AvatarFlightController.State(0.0, 0.0, -14.0, 0L, 0L);
        AvatarFlightController.Output output = null;
        for (int tick = 0; tick < 20; tick++) {
            output = update(
                    state,
                    input(1.0, false, false, false, false, Math.toRadians(-55.0))
            );
            state = new AvatarFlightController.State(
                    output.velocityX(),
                    output.velocityY(),
                    output.velocityZ(),
                    output.nextJumpAtMs(),
                    output.nextBoostAtMs()
            );
        }

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(horizontalSpeed > CONFIG.getMovement().getMaxForwardSpeed(),
                "pitch-down should still spend altitude for some speed above normal cruise");
        assertTrue(horizontalSpeed <= CONFIG.getMovement().getMaxGlideSpeed() + 0.00001,
                "unboosted pitch-down must respect the glide-only speed ceiling");
        assertTrue(horizontalSpeed < AvatarFlightSpeedMetrics.fastRechargeThreshold(CONFIG),
                "unboosted pitch-down must not reach the fast-flight recharge band");
        assertTrue(output.velocityY() < 0.0);
    }

    @Test
    void shortDivePullUpLoopStillLosesAltitudeWithoutVigour() {
        AvatarFlightController.State state = new AvatarFlightController.State(
                0.0,
                0.0,
                -CONFIG.getMovement().getNeutralGlideSpeed(),
                0L,
                0L
        );
        double altitudeChange = 0.0;
        AvatarFlightController.Output output = null;

        for (int cycle = 0; cycle < 10; cycle++) {
            for (int tick = 0; tick < 3; tick++) {
                output = update(state, input(1.0, false, false, false, false, Math.toRadians(-55.0)));
                altitudeChange += output.velocityY() * 0.1;
                state = stateFrom(output);
            }
            for (int tick = 0; tick < 7; tick++) {
                output = update(state, input(1.0, false, false, false, false, Math.toRadians(45.0)));
                altitudeChange += output.velocityY() * 0.1;
                state = stateFrom(output);
            }
        }

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(altitudeChange < -1.0,
                "short dive/pull-up loops must not sustain altitude indefinitely without Vigour; altitudeChange="
                        + altitudeChange + ", horizontalSpeed=" + horizontalSpeed);
        assertTrue(horizontalSpeed < AvatarFlightSpeedMetrics.fastRechargeThreshold(CONFIG),
                "dive-only speed loops must stay below the fast-recharge band");
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
    void shortDiveDoesNotImmediatelyReachLargeSpeedGain() {
        AvatarFlightController.State state = new AvatarFlightController.State(
                0.0, 0.0, -6.0, 0L, 0L, 0.0, 0.0, 0L
        );
        AvatarFlightController.Output output = null;
        double altitudeChange = 0.0;
        for (int tick = 0; tick < 8; tick++) {
            output = AvatarFlightController.update(
                    state,
                    input(1.0, false, false, false, false, Math.toRadians(-70.0)),
                    CONFIG,
                    0.1,
                    1000L + tick * 100L
            );
            altitudeChange += output.velocityY() * 0.1;
            state = stateFrom(output);
        }

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(horizontalSpeed < 7.0, "0.75s dive should not provide a large speed payoff");
        assertTrue(altitudeChange > -3.0, "short dive should not spend a large altitude chunk immediately");
    }

    @Test
    void sustainedSteepDiveBuildsSpeedWithoutCrossingNaturalCap() {
        AvatarFlightController.State state = new AvatarFlightController.State(
                0.0, 0.0, -6.0, 0L, 0L, 0.0, 0.0, 0L
        );
        AvatarFlightController.Output output = null;
        for (int tick = 0; tick < 30; tick++) {
            output = AvatarFlightController.update(
                    state,
                    input(1.0, false, false, false, false, Math.toRadians(-70.0)),
                    CONFIG,
                    0.1,
                    1000L + tick * 100L
            );
            state = stateFrom(output);
        }

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(horizontalSpeed > 11.0, "3s steep dive should build meaningful speed");
        assertTrue(horizontalSpeed <= CONFIG.getMovement().getMaxGlideSpeed() + 0.00001);
        assertTrue(horizontalSpeed < AvatarFlightSpeedMetrics.fastRechargeThreshold(CONFIG));
    }

    @Test
    void sustainedDiveThenModeratePullUpRecoversAboutSeventyPercentAltitude() {
        AvatarFlightController.State state = new AvatarFlightController.State(
                0.0, 0.0, -6.0, 0L, 0L, 0.0, 0.0, 0L
        );
        double diveAltitude = 0.0;
        for (int tick = 0; tick < 30; tick++) {
            AvatarFlightController.Output output = AvatarFlightController.update(
                    state,
                    input(1.0, false, false, false, false, Math.toRadians(-70.0)),
                    CONFIG,
                    0.1,
                    1000L + tick * 100L
            );
            diveAltitude += output.velocityY() * 0.1;
            state = stateFrom(output);
        }

        double climbAltitude = 0.0;
        AvatarFlightController.Output output = null;
        for (int tick = 0; tick < 50; tick++) {
            output = AvatarFlightController.update(
                    state,
                    input(1.0, false, false, false, false, Math.toRadians(45.0)),
                    CONFIG,
                    0.1,
                    5000L + tick * 100L
            );
            climbAltitude += output.velocityY() * 0.1;
            state = stateFrom(output);
        }

        double recovery = climbAltitude / Math.abs(diveAltitude);
        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(recovery > 0.60 && recovery < 0.85,
                "clean unboosted maneuver should recover around 70% of altitude, not all of it; recovery="
                        + recovery + ", diveAltitude=" + diveAltitude + ", climbAltitude=" + climbAltitude
                        + ", horizontalSpeed=" + horizontalSpeed);
        assertTrue(horizontalSpeed < CONFIG.getMovement().getNeutralGlideSpeed() + 1.0,
                "the climb should spend most stored speed by the end");
    }

    @Test
    void qBoostPointedUpAddsCappedLift() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L),
                input(1.0, false, false, true, false, Math.toRadians(60.0))
        );

        assertTrue(output.boostApplied());
        assertTrue(output.velocityY() > 0.0);
        assertTrue(output.velocityY() <= 3.0 + 0.00001,
                "upward boost lift must be capped so flap remains the stronger vertical tool");
    }

    @Test
    void qBoostPointedDownAddsDownwardThrust() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L),
                input(1.0, false, false, true, false, Math.toRadians(-60.0))
        );

        assertTrue(output.boostApplied());
        assertTrue(output.velocityY() < -5.0,
                "downward boost should use full directional thrust because it spends altitude");
    }

    @Test
    void boostedExcessDecaysWhenBoostWindowEnds() {
        double boostedSpeed = AvatarFlightSpeedMetrics.boostedHorizontalCap(CONFIG);
        AvatarFlightController.Output output = AvatarFlightController.update(
                new AvatarFlightController.State(0.0, 0.0, -boostedSpeed, 0L, 0L),
                input(1.0, false, false, false, false, 0.0),
                CONFIG,
                0.5,
                10_000L
        );

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(horizontalSpeed < boostedSpeed);
        assertTrue(horizontalSpeed >= CONFIG.getMovement().getMaxGlideSpeed());
    }

    @Test
    void chargedLaunchAppliesConfiguredImpulseFromGround() {
        AvatarFlightController.Output output = AvatarFlightController.update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0L),
                new AvatarFlightController.Input(0.0, 0.0, 0.0, false, false, false,
                        false, true, 0.0, 0.0, true, true, true, 3000L),
                CONFIG,
                0.1,
                1000L
        );

        assertTrue(output.launchApplied());
        assertEquals(AvatarFlightMode.LAUNCHING, output.mode());
        assertEquals(18.0, output.velocityY(), 0.00001);
        assertEquals(-11.0, output.velocityZ(), 0.00001);
        assertEquals(2.0, output.launchCost(), 0.00001);
        assertTrue(output.applyVelocity());
    }

    @Test
    void launchBelowChargeThresholdDoesNotApply() {
        AvatarFlightController.Output output = AvatarFlightController.update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0L),
                new AvatarFlightController.Input(0.0, 0.0, 0.0, false, false, false,
                        false, true, 0.0, 0.0, true, true, true, 499L),
                CONFIG,
                0.1,
                1000L
        );

        assertFalse(output.launchApplied());
        assertEquals(AvatarFlightMode.GROUNDED, output.mode());
    }

    @Test
    void pitchAndLateralVelocityProduceVisualPose() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, 0.0, 0L, 0L),
                input(0.0, 1.0, false, false, false, false, Math.toRadians(80.0))
        );

        assertEquals(Math.toRadians(80.0), output.visualPitchRadians(), 0.00001);
        assertTrue(Math.abs(output.visualRollRadians()) > 0.0);
        assertTrue(Math.abs(output.visualRollRadians()) <= Math.toRadians(30.0));
        assertTrue(Math.abs(output.visualRollRadians()) > Math.toRadians(10.0),
                "full lateral input should bank visibly enough to leave the shallow pose bucket");
    }

    @Test
    void yawTurnBanksModelEvenWithoutStrafeInput() {
        AvatarFlightController.Output rightTurn = AvatarFlightController.update(
                new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L),
                new AvatarFlightController.Input(1.0, 0.0, 0.0, false, false, false,
                        false, false, Math.toRadians(-90.0), 0.0, true, true),
                CONFIG,
                0.1,
                1000L
        );
        AvatarFlightController.Output leftTurn = AvatarFlightController.update(
                new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L),
                new AvatarFlightController.Input(1.0, 0.0, 0.0, false, false, false,
                        false, false, Math.toRadians(90.0), 0.0, true, true),
                CONFIG,
                0.1,
                1000L
        );

        assertTrue(Math.abs(rightTurn.visualRollRadians()) > 0.0,
                "turning right relative to current trajectory should bank the transformed model");
        assertTrue(Math.abs(leftTurn.visualRollRadians()) > 0.0,
                "turning left relative to current trajectory should bank the transformed model");
        assertTrue(Math.signum(rightTurn.visualRollRadians()) != Math.signum(leftTurn.visualRollRadians()),
                "left and right turns should bank in opposite directions");
    }

    @Test
    void sharpYawTurnCanReachHighestBankPoseBucket() {
        AvatarFlightController.Output output = AvatarFlightController.update(
                new AvatarFlightController.State(0.0, 0.0, -10.0, 0L, 0L),
                new AvatarFlightController.Input(1.0, 0.0, 0.0, false, false, false,
                        false, false, Math.toRadians(-90.0), 0.0, true, true),
                CONFIG,
                0.1,
                1000L
        );

        assertEquals(Math.toRadians(30.0), Math.abs(output.visualRollRadians()), 0.00001);
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

    @Test
    void sprintBoostCanExceedCruiseSpeed() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -CONFIG.getMovement().getMaxForwardSpeed(), 0L, 0L),
                input(1.0, false, false, true, false, 0.0)
        );

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(output.boostApplied());
        assertTrue(horizontalSpeed > CONFIG.getMovement().getMaxGlideSpeed(),
                "sprint boost must be the only path above the glide speed ceiling");
        assertTrue(horizontalSpeed <= CONFIG.getMovement().getMaxForwardSpeed()
                        + CONFIG.getBoost().getForwardImpulse(),
                "sprint boost should still respect the configured boosted speed ceiling");
    }

    @Test
    void boostIntentBlockedByResourceGateDoesNotExceedCruiseSpeed() {
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -CONFIG.getMovement().getMaxForwardSpeed(), 0L, 0L),
                input(1.0, 0.0, false, false, true, false, false, 0.0, true, false)
        );

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertFalse(output.boostApplied());
        assertTrue(horizontalSpeed <= CONFIG.getMovement().getMaxForwardSpeed(),
                "blocked sprint boost must stay at or below the normal cruise speed");
    }

    @Test
    void sprintPulseKeepsBoostActiveAfterClientClearsAirborneSprint() {
        AvatarFlightController.Output pulse = AvatarFlightController.update(
                new AvatarFlightController.State(0.0, 0.0, -CONFIG.getMovement().getMaxForwardSpeed(), 0L, 0L),
                input(1.0, false, false, true, false, 0.0),
                CONFIG,
                0.1,
                1000L
        );
        AvatarFlightController.Output heldWindow = AvatarFlightController.update(
                new AvatarFlightController.State(
                        pulse.velocityX(),
                        pulse.velocityY(),
                        pulse.velocityZ(),
                        pulse.nextJumpAtMs(),
                        pulse.nextBoostAtMs()
                ),
                input(1.0, false, false, false, false, 0.0),
                CONFIG,
                0.1,
                1100L
        );

        assertTrue(pulse.boostApplied());
        assertFalse(heldWindow.boostApplied());
        assertTrue(heldWindow.fastFlight(),
                "one-frame airborne sprint pulses should remain visibly boosted for the configured duration");
        assertTrue(Math.hypot(heldWindow.velocityX(), heldWindow.velocityZ())
                        > CONFIG.getMovement().getMaxForwardSpeed(),
                "boost window should keep accelerating above cruise even after the raw sprint flag clears");
    }

    @Test
    void boostedSpeedDecaysTowardGlideCeilingInsteadOfHardClamping() {
        double boostedSpeed = CONFIG.getMovement().getMaxForwardSpeed() + CONFIG.getBoost().getForwardImpulse();
        AvatarFlightController.Output output = update(
                new AvatarFlightController.State(0.0, 0.0, -boostedSpeed, 0L, 0L),
                input(1.0, false, false, false, false, 0.0)
        );

        double horizontalSpeed = Math.hypot(output.velocityX(), output.velocityZ());
        assertTrue(horizontalSpeed > CONFIG.getMovement().getMaxGlideSpeed(),
                "boosted speed should bleed down toward glide speed instead of disappearing in one tick");
        assertTrue(horizontalSpeed < boostedSpeed);
    }

    private static AvatarFlightController.Output update(AvatarFlightController.State state,
                                                        AvatarFlightController.Input input) {
        return AvatarFlightController.update(state, input, CONFIG, 0.1, 1000L);
    }

    private static AvatarFlightController.State stateFrom(AvatarFlightController.Output output) {
        return new AvatarFlightController.State(
                output.velocityX(),
                output.velocityY(),
            output.velocityZ(),
            output.nextJumpAtMs(),
            output.nextBoostAtMs(),
            output.diveLoad(),
            output.climbLoad(),
            output.nextLaunchAtMs()
        );
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
                pitchRadians,
                true,
                true
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
                pitchRadians,
                true,
                true
        );
    }

    private static AvatarFlightController.Input input(double forwardAxis,
                                                      double strafeAxis,
                                                      boolean jump,
                                                      boolean crouch,
                                                      boolean sprint,
                                                      boolean onGround,
                                                      boolean airbrake,
                                                      double pitchRadians,
                                                      boolean flapAllowed,
                                                      boolean boostAllowed) {
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
                pitchRadians,
                flapAllowed,
                boostAllowed
        );
    }
}

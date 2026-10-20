package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AvatarSwimControllerTest {
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.CODEC.decode(BsonDocument.parse("""
            {"Underwater":true,"Movement":{"MaxForwardSpeed":10,"ForwardAcceleration":4},
             "Boost":{"ForwardImpulse":6,"DurationSeconds":0.5,"CooldownSeconds":2},
             "Curve":{"BoostedSpeedDecay":3}}
            """), new ExtraInfo());

    @Test
    void acceleratesInWaterWithoutLaunchAndStopsAtCruise() {
        var output = update(rest(), input(1, 0, false, false, true), 0.5, 1000);
        assertTrue(output.applyVelocity());
        assertEquals(2, speed(output), 0.00001);
        output = update(state(output), input(1, 0, false, false, true), 10, 1500);
        assertEquals(10, speed(output), 0.00001);
        assertFalse(output.launchApplied());
        assertFalse(output.jumpApplied());
    }

    @Test
    void lookingUpOrDownChangesDirectionWithoutTradingSpeed() {
        for (double pitch : new double[] {-Math.PI / 3, 0, Math.PI / 3}) {
            var output = update(rest(), input(1, pitch, false, false, true), 3, 1000);
            assertEquals(10, speed(output), 0.00001);
            assertEquals(10 * Math.sin(pitch), output.velocityY(), 0.00001);
        }
    }

    @Test
    void boostExceedsCruiseThenDecaysAndCannotRepeatDuringCooldown() {
        var cruise = update(rest(), input(1, 0, false, false, true), 3, 1000);
        var boosted = update(state(cruise), input(1, 0, true, false, true), 0.1, 1100);
        assertTrue(boosted.boostApplied());
        assertEquals(16, speed(boosted), 0.00001);
        var repeated = update(state(boosted), input(1, 0, true, false, true), 0.1, 1200);
        assertFalse(repeated.boostApplied());
        assertEquals(16, speed(repeated), 0.00001);
        var expired = update(state(repeated), input(1, 0, false, false, true), 1, 2200);
        assertEquals(13, speed(expired), 0.00001);
        var settled = update(state(expired), input(1, 0, false, false, true), 2, 4200);
        assertEquals(10, speed(settled), 0.00001);
    }

    @Test
    void brakeAndLeavingWaterReleasePropulsion() {
        var moving = update(rest(), input(1, 0, false, false, true), 3, 1000);
        var brake = update(state(moving), input(1, 0, true, true, true), 10, 2000);
        assertEquals(0, speed(brake), 0.00001);
        assertEquals(0, brake.hudTargetSpeedRatio(), 0.00001);
        assertFalse(brake.boostApplied());
        var dry = update(state(moving), input(1, 0, true, false, false), 1, 2000);
        assertFalse(dry.applyVelocity());
        assertFalse(dry.boostApplied());
        assertEquals(0, speed(dry), 0.00001);
    }

    @Test
    void ordinaryFlightStillYieldsToNativeSwimming() {
        var output = AvatarFlightController.update(rest(), input(1, 0, true, false, true),
                TwAvatarFlightConfig.defaultConfig(), 1, 1000);
        assertFalse(output.applyVelocity());
    }

    @Test
    void inheritedUnderwaterProfileControlsMovementAndExplicitFalseRestoresFlight() {
        var child = TwAvatarFlightConfig.CODEC.decode(BsonDocument.parse("{}"), new ExtraInfo());
        child.inheritMissingTopLevelFrom(CONFIG, Set.of());
        assertTrue(AvatarFlightController.update(rest(), input(1, 0, false, false, true),
                child, 1, 1000).applyVelocity());
        var flightChild = TwAvatarFlightConfig.CODEC.decode(
                BsonDocument.parse("{\"Underwater\":false}"), new ExtraInfo());
        flightChild.inheritMissingTopLevelFrom(CONFIG, Set.of("Underwater"));
        assertFalse(AvatarFlightController.update(rest(), input(1, 0, false, false, true),
                flightChild, 1, 1000).applyVelocity());
    }

    @Test
    void diagonalAndVerticalMovementRespectTotalSpeedLimits() {
        var diagonal = new AvatarFlightController.Input(1, 1, 1, true, false, false, false,
                false, 0, 0, true, true, true, 0, false, true);
        var output = update(rest(), diagonal, 10, 1000);
        assertEquals(10, speed(output), 0.00001);
        assertTrue(output.velocityY() > 0);
        assertFalse(output.jumpApplied());
        var descending = new AvatarFlightController.Input(0, 0, 0, false, true, false, false,
                false, 0, 0, true, true, true, 0, false, true);
        output = update(rest(), descending, 10, 1000);
        assertEquals(-Math.min(10, CONFIG.getMovement().getDescendSpeed()), output.velocityY(), 0.00001);
    }

    @Test
    void boostAuthorizationPreventsOverspeedAndCooldownSpend() {
        var denied = new AvatarFlightController.Input(1, 0, 0, false, false, true, false,
                false, 0, 0, true, false, true, 0, false, true);
        var output = update(rest(), denied, 10, 1000);
        assertEquals(10, speed(output), 0.00001);
        assertFalse(output.boostApplied());
        assertEquals(0, output.nextBoostAtMs());
    }

    @Test
    void boostFromRestContinuesForDurationButWaterExitCancelsIt() {
        var initial = update(rest(), input(0, 0, true, false, true), 0.1, 1000);
        var continuing = update(state(initial), input(0, 0, false, false, true), 0.1, 1100);
        assertTrue(speed(continuing) > speed(initial));
        var dry = update(state(continuing), input(0, 0, false, false, false), 0.1, 1200);
        var wet = update(state(dry), input(0, 0, false, false, true), 0.1, 1300);
        var wetAgain = update(state(wet), input(0, 0, false, false, true), 0.1, 1400);
        assertEquals(0, speed(wetAgain), 0.00001);
        assertFalse(wetAgain.boostApplied());
        assertEquals(initial.nextBoostAtMs(), wetAgain.nextBoostAtMs());
    }

    @Test
    void reversingAfterCruisingRespectsBackwardSpeedLimit() {
        var cruise = update(rest(), input(1, 0, false, false, true), 3, 1000);
        var reverse = update(state(cruise), input(-1, 0, false, false, true), 0.05, 1050);
        assertTrue(reverse.velocityZ() > 0);
        assertTrue(speed(reverse) <= CONFIG.getMovement().getMaxBackwardSpeed());
    }

    @Test
    void companionBoostImpulseTuningStillAppliesUnderwater() {
        var tuned = new AvatarFlightProgressionTuning(1, 1, 1, 1.25, 1, 1);
        var output = AvatarFlightController.update(rest(), input(0, 0, true, false, true),
                CONFIG, tuned, 0, 1000);
        assertEquals(7.5, speed(output), 0.00001);
    }

    @Test
    void verticalSwimmingHudUsesFullSpeedAndHidesLaunchCharge() {
        var flight = new AvatarFlightComponent();
        flight.setVelocity(0, 10, 0);
        var input = new AvatarFlightInputComponent();
        input.beginLaunchCharge(1000);
        input.setOnGround(true);
        var hud = AvatarFlightHudSystem.buildModel(flight, input, CONFIG,
                AvatarFlightProgressionTuning.neutral(), 1500);
        assertEquals(10.0 / 16, hud.speedRatio(), 0.00001);
        assertTrue(hud.underwater());
        assertFalse(hud.launchChargeVisible());
    }

    private static AvatarFlightController.Output update(AvatarFlightController.State state,
            AvatarFlightController.Input input, double dt, long now) {
        return AvatarFlightController.update(state, input, CONFIG, dt, now);
    }

    private static AvatarFlightController.Input input(double forward, double pitch, boolean boost,
            boolean brake, boolean water) {
        return new AvatarFlightController.Input(forward, 0, 0, false, false, boost, brake,
                false, 0, pitch, true, true, true, 0, brake, water);
    }

    private static AvatarFlightController.State rest() {
        return new AvatarFlightController.State(0, 0, 0, 0, 0, 0, 0, 0, AvatarFlightMode.GROUNDED);
    }

    private static AvatarFlightController.State state(AvatarFlightController.Output output) {
        return new AvatarFlightController.State(output.velocityX(), output.velocityY(), output.velocityZ(),
                output.nextJumpAtMs(), output.nextBoostAtMs(), output.diveLoad(), output.climbLoad(),
                output.nextLaunchAtMs(), output.mode());
    }

    private static double speed(AvatarFlightController.Output output) {
        return Math.sqrt(output.velocityX() * output.velocityX() + output.velocityY() * output.velocityY()
                + output.velocityZ() * output.velocityZ());
    }
}

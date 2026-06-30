package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountedGlidePhysicsTest {

    @Test
    void heldJumpFlapsOnlyWhenCooldownExpires() {
        TwMountedGlideConfig config = new TwMountedGlideConfig();
        MountedGlidePhysicsState state = MountedGlidePhysicsState.from(config);

        MountedGlidePhysics.Output first = MountedGlidePhysics.update(
                state,
                config,
                new MountedGlidePhysics.Input(0.0, 1.0, 0.0, true, false, false),
                0.1
        );
        MountedGlidePhysics.Output second = MountedGlidePhysics.update(
                state,
                config,
                new MountedGlidePhysics.Input(0.0, 1.0, 0.0, true, false, false),
                0.1
        );
        MountedGlidePhysics.Output afterCooldown = MountedGlidePhysics.update(
                state,
                config,
                new MountedGlidePhysics.Input(0.0, 1.0, 0.0, true, false, false),
                config.getFlap().getCooldownSeconds()
        );

        assertTrue(first.flapped());
        assertFalse(second.flapped());
        assertTrue(afterCooldown.flapped());
    }

    @Test
    void sprintHeldAtFlapTimeCreatesForwardFlap() {
        TwMountedGlideConfig config = new TwMountedGlideConfig();
        MountedGlidePhysicsState state = MountedGlidePhysicsState.from(config);

        MountedGlidePhysics.Output output = MountedGlidePhysics.update(
                state,
                config,
                new MountedGlidePhysics.Input(0.0, 1.0, 0.0, true, true, false),
                0.1
        );

        assertTrue(output.flapped());
        assertTrue(output.forwardFlap());
        assertTrue(state.getGlideSpeed() > config.getGlide().getBaseSpeed());
    }

    @Test
    void pitchDownGainsSpeedAndSink() {
        TwMountedGlideConfig config = new TwMountedGlideConfig();
        MountedGlidePhysicsState state = MountedGlidePhysicsState.from(config);

        MountedGlidePhysics.update(
                state,
                config,
                new MountedGlidePhysics.Input(Math.toRadians(-35.0), 1.0, 0.0, false, false, false),
                0.5
        );

        assertTrue(state.getGlideSpeed() > config.getGlide().getBaseSpeed());
        assertTrue(state.getVerticalVelocity() < -config.getGlide().getPassiveSinkRate());
    }

    @Test
    void pitchUpTradesSpeedForLift() {
        TwMountedGlideConfig config = new TwMountedGlideConfig();
        MountedGlidePhysicsState state = MountedGlidePhysicsState.from(config);
        state.setGlideSpeed(config.getGlide().getBaseSpeed() + 8.0);

        MountedGlidePhysics.update(
                state,
                config,
                new MountedGlidePhysics.Input(Math.toRadians(32.0), 1.0, 0.0, false, false, false),
                0.5
        );

        assertTrue(state.getVerticalVelocity() > 0.0);
        assertTrue(state.getGlideSpeed() < config.getGlide().getBaseSpeed() + 8.0);
    }

    @Test
    void pitchUpWithoutSpeedStalls() {
        TwMountedGlideConfig config = new TwMountedGlideConfig();
        MountedGlidePhysicsState state = MountedGlidePhysicsState.from(config);
        state.setGlideSpeed(config.getGlide().getMinSpeed());

        MountedGlidePhysics.Output output = MountedGlidePhysics.update(
                state,
                config,
                new MountedGlidePhysics.Input(Math.toRadians(38.0), 1.0, 0.0, false, false, false),
                0.5
        );

        assertTrue(output.stalled());
        assertTrue(state.getVerticalVelocity() < -config.getGlide().getPassiveSinkRate());
    }

    @Test
    void crouchAirbrakeDecaysSpeed() throws Exception {
        TwMountedGlideConfig config = new TwMountedGlideConfig();
        setField(config.getAirbrake(), "speedDecay", 12.0);
        MountedGlidePhysicsState state = MountedGlidePhysicsState.from(config);
        state.setGlideSpeed(config.getGlide().getBaseSpeed() + 10.0);

        MountedGlidePhysics.Output output = MountedGlidePhysics.update(
                state,
                config,
                new MountedGlidePhysics.Input(0.0, 1.0, 0.0, false, false, true),
                0.5
        );

        assertTrue(output.airbraking());
        assertEquals(config.getGlide().getBaseSpeed() + 4.0, state.getGlideSpeed(), 0.0001);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightMovementSystemTest {
    private static final double EPSILON = 0.00001;

    @Test
    void authorizeVigourPrefersFlapWhenSameTickBoostWouldExceedCharges() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        AvatarFlightComponent flight = new AvatarFlightComponent("default", 1000L);
        flight.setVigourCharges(config.getVigour().getUpwardFlapCost());
        flight.setLastVigourUpdateAtMs(1000L);
        flight.setNextJumpAtMs(0L);
        flight.setNextBoostAtMs(0L);

        AvatarFlightController.Input authorized = authorizeVigour(
                input(true, true, false),
                flight,
                config,
                1000L
        );

        assertTrue(authorized.flapAllowed());
        assertFalse(authorized.boostAllowed(),
                "same-tick boost must be blocked when flap consumes the only available charge");
    }

    @Test
    void authorizeVigourDoesNotReserveCooldownBlockedFlapCostAgainstBoost() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        AvatarFlightComponent flight = new AvatarFlightComponent("default", 1000L);
        flight.setVigourCharges(config.getVigour().getForwardBoostCost());
        flight.setLastVigourUpdateAtMs(1000L);
        flight.setNextJumpAtMs(2000L);
        flight.setNextBoostAtMs(0L);

        AvatarFlightController.Input authorized = authorizeVigour(
                input(true, true, false),
                flight,
                config,
                1000L
        );

        assertTrue(authorized.boostAllowed(),
                "holding jump during flap cooldown must not reserve charge away from an eligible boost");
    }

    @Test
    void authorizeVigourDoesNotReserveUnaffordableFlapCostAgainstBoost() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setNestedField(config, "vigour", "upwardFlapCost", 2.0);
        setNestedField(config, "vigour", "forwardBoostCost", 1.0);
        AvatarFlightComponent flight = new AvatarFlightComponent("default", 1000L);
        flight.setVigourCharges(1.0);
        flight.setLastVigourUpdateAtMs(1000L);
        flight.setNextJumpAtMs(0L);
        flight.setNextBoostAtMs(0L);

        AvatarFlightController.Input authorized = authorizeVigour(
                input(true, true, false),
                flight,
                config,
                1000L
        );

        assertFalse(authorized.flapAllowed());
        assertTrue(authorized.boostAllowed(),
                "an unaffordable same-tick flap must not block an otherwise affordable boost");
    }

    @Test
    void disabledVigourSpendLeavesFreeResourceStateAtNoneMode() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        setNestedField(config, "vigour", "enabled", false);
        AvatarFlightComponent flight = new AvatarFlightComponent("default", 1000L);
        flight.setVigourCharges(config.getVigour().getMaxCharges());
        flight.setLastVigourUpdateAtMs(1000L);
        flight.setVigourRechargeBlockedUntilMs(0L);
        flight.setVigourRechargeMode(AvatarFlightVigourService.RechargeMode.NONE.name());

        spendAppliedVigour(flight, config, output(true, true), 1000L);

        assertEquals(config.getVigour().getMaxCharges(), flight.getVigourCharges(), EPSILON);
        assertEquals(0L, flight.getVigourRechargeBlockedUntilMs());
        assertEquals(AvatarFlightVigourService.RechargeMode.NONE.name(), flight.getVigourRechargeMode());
    }

    @Test
    void launchIsBlockedWhenVigourCannotPayCost() throws Exception {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();
        AvatarFlightComponent flight = new AvatarFlightComponent("default", 1000L);
        flight.setVigourCharges(0.0);
        flight.setLastVigourUpdateAtMs(1000L);
        AvatarFlightController.Input input = new AvatarFlightController.Input(
                0.0,
                0.0,
                0.0,
                false,
                false,
                false,
                false,
                true,
                0.0,
                0.0,
                true,
                true,
                true,
                3000L
        );

        AvatarFlightController.Input authorized = authorizeVigour(input, flight, config, 2000L);

        assertFalse(authorized.launchAllowed());
    }

    @Test
    void groundedMovementIntentRequiresGroundedInputBeyondDeadzone() {
        TwAvatarFlightConfig config = TwAvatarFlightConfig.defaultConfig();

        assertFalse(AvatarFlightMovementSystem.hasGroundedMovementIntent(
                movementInput(0.0, 0.0, true), config));
        assertFalse(AvatarFlightMovementSystem.hasGroundedMovementIntent(
                movementInput(config.getInput().getForwardDeadzone(), 0.0, true), config));
        assertTrue(AvatarFlightMovementSystem.hasGroundedMovementIntent(
                movementInput(config.getInput().getForwardDeadzone() + 0.01, 0.0, true), config));
        assertTrue(AvatarFlightMovementSystem.hasGroundedMovementIntent(
                movementInput(0.0, -config.getInput().getStrafeDeadzone() - 0.01, true), config));
        assertFalse(AvatarFlightMovementSystem.hasGroundedMovementIntent(
                movementInput(1.0, 0.0, false), config));
    }

    private static AvatarFlightController.Input authorizeVigour(AvatarFlightController.Input input,
                                                               AvatarFlightComponent flight,
                                                               TwAvatarFlightConfig config,
                                                               long now) throws Exception {
        Method method = AvatarFlightMovementSystem.class.getDeclaredMethod(
                "authorizeVigour",
                AvatarFlightController.Input.class,
                AvatarFlightComponent.class,
                TwAvatarFlightConfig.class,
                long.class
        );
        method.setAccessible(true);
        return (AvatarFlightController.Input) method.invoke(null, input, flight, config, now);
    }

    private static void spendAppliedVigour(AvatarFlightComponent flight,
                                           TwAvatarFlightConfig config,
                                           AvatarFlightController.Output output,
                                           long now) throws Exception {
        Method method = AvatarFlightMovementSystem.class.getDeclaredMethod(
                "spendAppliedVigour",
                AvatarFlightComponent.class,
                TwAvatarFlightConfig.class,
                AvatarFlightController.Output.class,
                long.class
        );
        method.setAccessible(true);
        method.invoke(null, flight, config, output, now);
    }

    private static AvatarFlightController.Input input(boolean jump, boolean sprint, boolean airbrake) {
        return new AvatarFlightController.Input(
                1.0,
                0.0,
                0.0,
                jump,
                false,
                sprint,
                airbrake,
                false,
                0.0,
                0.0,
                true,
                true
        );
    }

    private static AvatarFlightController.Input movementInput(double forwardAxis,
                                                               double strafeAxis,
                                                               boolean onGround) {
        return new AvatarFlightController.Input(
                forwardAxis,
                strafeAxis,
                0.0,
                false,
                false,
                false,
                false,
                onGround,
                0.0,
                0.0,
                true,
                true
        );
    }

    private static AvatarFlightController.Output output(boolean jumpApplied, boolean boostApplied) {
        return new AvatarFlightController.Output(
                AvatarFlightMode.FORWARD_FLIGHT,
                0.0,
                0.0,
                0.0,
                0L,
                0L,
                true,
                jumpApplied,
                boostApplied,
                false,
                boostApplied,
                0.0,
                0.0
        );
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

package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightVigourServiceTest {
    private static final double EPSILON = 0.00001;
    private static final TwAvatarFlightConfig CONFIG = TwAvatarFlightConfig.defaultConfig();

    @Test
    void groundedRechargeRecoversOneDefaultCharge() {
        AvatarFlightVigourService.Result result = AvatarFlightVigourService.recharge(
                new AvatarFlightVigourService.State(2.0, 1000L, 0L),
                CONFIG,
                true,
                0.0,
                5000L
        );

        assertEquals(AvatarFlightVigourService.RechargeMode.GROUNDED, result.mode());
        assertEquals(3.0, result.state().charges(), EPSILON);
        assertEquals(5000L, result.state().lastUpdateAtMs());
        assertEquals(0L, result.state().rechargeBlockedUntilMs());
    }

    @Test
    void fastFlightRechargeRecoversOneDefaultCharge() {
        AvatarFlightVigourService.Result result = AvatarFlightVigourService.recharge(
                new AvatarFlightVigourService.State(2.0, 1000L, 0L),
                CONFIG,
                false,
                16.0,
                9000L
        );

        assertEquals(AvatarFlightVigourService.RechargeMode.FAST_FLIGHT, result.mode());
        assertEquals(3.0, result.state().charges(), EPSILON);
        assertEquals(9000L, result.state().lastUpdateAtMs());
    }

    @Test
    void ordinaryAirborneCruiseDoesNotRecharge() {
        AvatarFlightVigourService.Result result = AvatarFlightVigourService.recharge(
                new AvatarFlightVigourService.State(2.0, 1000L, 0L),
                CONFIG,
                false,
                14.0,
                9000L
        );

        assertEquals(AvatarFlightVigourService.RechargeMode.NONE, result.mode());
        assertEquals(2.0, result.state().charges(), EPSILON);
        assertEquals(9000L, result.state().lastUpdateAtMs());
    }

    @Test
    void spendDelayBlocksRechargeUntilOrderingPassesBlockedTimestamp() {
        AvatarFlightVigourService.State spent = AvatarFlightVigourService.spend(
                new AvatarFlightVigourService.State(5.0, 1000L, 1000L),
                CONFIG,
                1.0,
                1000L
        );

        assertEquals(4.0, spent.charges(), EPSILON);
        assertEquals(1000L, spent.lastUpdateAtMs());
        assertEquals(1750L, spent.rechargeBlockedUntilMs());

        AvatarFlightVigourService.Result delayed = AvatarFlightVigourService.recharge(
                spent,
                CONFIG,
                false,
                16.0,
                1700L
        );

        assertEquals(AvatarFlightVigourService.RechargeMode.DELAYED, delayed.mode());
        assertEquals(4.0, delayed.state().charges(), EPSILON);
        assertEquals(1700L, delayed.state().lastUpdateAtMs());
        assertEquals(1750L, delayed.state().rechargeBlockedUntilMs());

        AvatarFlightVigourService.Result recovered = AvatarFlightVigourService.recharge(
                delayed.state(),
                CONFIG,
                false,
                16.0,
                9750L
        );

        assertEquals(AvatarFlightVigourService.RechargeMode.FAST_FLIGHT, recovered.mode());
        assertEquals(5.00625, recovered.state().charges(), EPSILON);
        assertTrue(recovered.state().charges() < 6.0, "post-delay fast recharge must not restore two charges");
        assertEquals(9750L, recovered.state().lastUpdateAtMs());
    }

    @Test
    void spendGatesAtZeroAndInsufficientCharges() throws Exception {
        AvatarFlightVigourService.State empty = new AvatarFlightVigourService.State(0.0, 1000L, 0L);

        assertTrue(AvatarFlightVigourService.canSpend(empty, CONFIG, 0.0));
        assertTrue(AvatarFlightVigourService.canSpend(empty, CONFIG, -1.0));
        assertFalse(AvatarFlightVigourService.canSpend(empty, CONFIG, 1.0));

        AvatarFlightVigourService.State insufficient = AvatarFlightVigourService.spend(empty, CONFIG, 1.0, 2000L);
        assertEquals(0.0, insufficient.charges(), EPSILON);
        assertEquals(2000L, insufficient.lastUpdateAtMs());
        assertEquals(0L, insufficient.rechargeBlockedUntilMs());

        TwAvatarFlightConfig disabled = TwAvatarFlightConfig.defaultConfig();
        setNestedField(disabled, "vigour", "enabled", false);
        assertTrue(AvatarFlightVigourService.canSpend(empty, disabled, 10.0));

        AvatarFlightVigourService.State freeSpend = AvatarFlightVigourService.spend(
                new AvatarFlightVigourService.State(2.0, 1000L, 0L),
                disabled,
                1.0,
                2000L
        );
        assertEquals(2.0, freeSpend.charges(), EPSILON);
        assertEquals(2000L, freeSpend.lastUpdateAtMs());
        assertEquals(0L, freeSpend.rechargeBlockedUntilMs());
    }

    @Test
    void negativeTimestampsPreserveSpendDelayOrdering() {
        AvatarFlightVigourService.State spent = AvatarFlightVigourService.spend(
                new AvatarFlightVigourService.State(5.0, -5000L, 0L),
                CONFIG,
                1.0,
                -4000L
        );

        assertEquals(4.0, spent.charges(), EPSILON);
        assertEquals(-4000L, spent.lastUpdateAtMs());
        assertEquals(-3250L, spent.rechargeBlockedUntilMs());

        AvatarFlightVigourService.Result delayed = AvatarFlightVigourService.recharge(
                spent,
                CONFIG,
                false,
                16.0,
                -3500L
        );

        assertEquals(AvatarFlightVigourService.RechargeMode.DELAYED, delayed.mode());
        assertEquals(4.0, delayed.state().charges(), EPSILON);
        assertEquals(-3500L, delayed.state().lastUpdateAtMs());
        assertEquals(-3250L, delayed.state().rechargeBlockedUntilMs());

        AvatarFlightVigourService.Result afterDelay = AvatarFlightVigourService.recharge(
                delayed.state(),
                CONFIG,
                false,
                16.0,
                -3000L
        );

        assertEquals(AvatarFlightVigourService.RechargeMode.FAST_FLIGHT, afterDelay.mode());
        assertEquals(4.0625, afterDelay.state().charges(), EPSILON);
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

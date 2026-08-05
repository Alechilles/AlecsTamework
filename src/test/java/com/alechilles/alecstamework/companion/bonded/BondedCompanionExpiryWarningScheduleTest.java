package com.alechilles.alecstamework.companion.bonded;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BondedCompanionExpiryWarningScheduleTest {
    @Test
    void warns_at_the_requested_yellow_thresholds() {
        assertEquals(new BondedCompanionExpiryWarningSchedule.Warning(60, NotificationStyle.Warning),
                BondedCompanionExpiryWarningSchedule.warning(160_000L, 100_000L).orElseThrow());
        assertEquals(new BondedCompanionExpiryWarningSchedule.Warning(30, NotificationStyle.Warning),
                BondedCompanionExpiryWarningSchedule.warning(130_000L, 100_000L).orElseThrow());
        assertEquals(new BondedCompanionExpiryWarningSchedule.Warning(10, NotificationStyle.Warning),
                BondedCompanionExpiryWarningSchedule.warning(110_000L, 100_000L).orElseThrow());
    }

    @Test
    void warns_at_each_requested_red_threshold() {
        assertEquals(new BondedCompanionExpiryWarningSchedule.Warning(5, NotificationStyle.Danger),
                BondedCompanionExpiryWarningSchedule.warning(105_000L, 100_000L).orElseThrow());
        assertEquals(new BondedCompanionExpiryWarningSchedule.Warning(1, NotificationStyle.Danger),
                BondedCompanionExpiryWarningSchedule.warning(101_000L, 100_000L).orElseThrow());
    }

    @Test
    void ignores_unlimited_expired_and_non_threshold_leases() {
        assertTrue(BondedCompanionExpiryWarningSchedule.warning(0L, 100_000L).isEmpty());
        assertTrue(BondedCompanionExpiryWarningSchedule.warning(100_000L, 100_000L).isEmpty());
        assertTrue(BondedCompanionExpiryWarningSchedule.warning(106_000L, 100_000L).isEmpty());
        assertEquals(10, BondedCompanionExpiryWarningSchedule.warning(110_999L, 100_000L)
                .orElseThrow().secondsRemaining());
        assertTrue(BondedCompanionExpiryWarningSchedule.warning(
                Long.MAX_VALUE, 100_000L).isEmpty());
    }

    @Test
    void selects_a_configured_model_effect_only_for_the_thirty_second_warning() {
        BondedCompanionExpiryWarningSchedule.Warning thirtySeconds =
                BondedCompanionExpiryWarningSchedule.warning(130_000L, 100_000L)
                        .orElseThrow();
        BondedCompanionExpiryWarningSchedule.Warning tenSeconds =
                BondedCompanionExpiryWarningSchedule.warning(110_000L, 100_000L)
                        .orElseThrow();

        assertEquals("HyDragon_Dragon_Desummon", BondedCompanionExpiryWarningSchedule
                .modelEffectId(thirtySeconds, " HyDragon_Dragon_Desummon ")
                .orElseThrow());
        assertTrue(BondedCompanionExpiryWarningSchedule
                .modelEffectId(tenSeconds, "HyDragon_Dragon_Desummon").isEmpty());
        assertTrue(BondedCompanionExpiryWarningSchedule
                .modelEffectId(thirtySeconds, " ").isEmpty());
    }
}

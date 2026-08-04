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
    }
}

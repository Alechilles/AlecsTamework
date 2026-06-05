package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for diagnostics used to investigate instant death after revive/lost recovery.
 */
class RecentRespawnTraceServiceTest {
    @Test
    void storesRecentTraceByReplacementNpcAndExpiresOldEntries() {
        RecentRespawnTraceService service = new RecentRespawnTraceService(10_000L);
        UUID originalNpcUuid = UUID.randomUUID();
        UUID replacementNpcUuid = UUID.randomUUID();

        RecentRespawnTraceService.Trace trace = service.startTrace(
                "lost_snapshot_recovery",
                originalNpcUuid,
                UUID.randomUUID(),
                "Tamed_Rat",
                "tool-alpha",
                1_000L
        );
        service.recordReplacementNpc(trace, replacementNpcUuid, 1_250L);

        RecentRespawnTraceService.Trace recent = service.getRecentTrace(replacementNpcUuid, 2_000L);
        assertNotNull(recent);
        assertEquals(originalNpcUuid, recent.originalNpcUuid());
        assertEquals(replacementNpcUuid, recent.replacementNpcUuid());
        assertEquals("lost_snapshot_recovery", recent.branch());

        assertNull(service.getRecentTrace(replacementNpcUuid, 12_001L));
    }

    @Test
    void recordsOnlyFirstDamageEventForTrace() {
        RecentRespawnTraceService service = new RecentRespawnTraceService(10_000L);
        UUID replacementNpcUuid = UUID.randomUUID();
        RecentRespawnTraceService.Trace trace = service.startTrace(
                "dead_respawn",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Tamed_Rat",
                "tool-alpha",
                1_000L
        );
        service.recordReplacementNpc(trace, replacementNpcUuid, 1_000L);

        service.recordFirstDamage(
                replacementNpcUuid,
                new RecentRespawnTraceService.DamageEvent("PLAYER:Alice", "PHYSICAL", "4.000", "20.000->16.000"),
                1_100L
        );
        service.recordFirstDamage(
                replacementNpcUuid,
                new RecentRespawnTraceService.DamageEvent("NPC:Wolf", "PHYSICAL", "9.000", "16.000->7.000"),
                1_200L
        );

        RecentRespawnTraceService.Trace recent = service.getRecentTrace(replacementNpcUuid, 1_300L);
        assertNotNull(recent);
        assertNotNull(recent.firstDamage());
        assertEquals("PLAYER:Alice", recent.firstDamage().attacker());
        assertTrue(service.describe(recent, 1_300L).contains("firstDamage=attacker=PLAYER:Alice"));
    }
}

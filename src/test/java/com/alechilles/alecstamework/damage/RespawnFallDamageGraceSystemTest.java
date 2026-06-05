package com.alechilles.alecstamework.damage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.items.RecentRespawnTraceService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RespawnFallDamageGraceSystemTest {
    @Test
    void graceOnlyAppliesAfterReplacementWithinShortWindow() {
        RecentRespawnTraceService service = RecentRespawnTraceService.getInstance();
        UUID replacementUuid = UUID.randomUUID();
        RecentRespawnTraceService.Trace trace = service.startTrace(
                "dead_respawn",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Tamed_Chicken",
                "tool",
                1_000L
        );

        assertFalse(RespawnFallDamageGraceSystem.isWithinFallDamageGrace(trace, 1_050L));

        trace = service.recordReplacementNpc(trace, replacementUuid, 1_100L);
        assertTrue(RespawnFallDamageGraceSystem.isWithinFallDamageGrace(trace, 1_100L));
        assertTrue(RespawnFallDamageGraceSystem.isWithinFallDamageGrace(trace, 3_100L));
        assertFalse(RespawnFallDamageGraceSystem.isWithinFallDamageGrace(trace, 3_101L));

        service.clear(replacementUuid);
    }

    @Test
    void graceAppliesToRecentGenericSpawnProtection() {
        UUID offspringUuid = UUID.randomUUID();
        RecentSpawnProtectionService.Protection protection =
                new RecentSpawnProtectionService.Protection(offspringUuid, "breeding_offspring", "Tamed_Chicken", 2_000L);

        assertTrue(RespawnFallDamageGraceSystem.isWithinSpawnProtectionGrace(protection, 2_000L));
        assertTrue(RespawnFallDamageGraceSystem.isWithinSpawnProtectionGrace(protection, 4_000L));
        assertFalse(RespawnFallDamageGraceSystem.isWithinSpawnProtectionGrace(protection, 4_001L));
    }
}

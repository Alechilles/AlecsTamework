package com.alechilles.alecstamework.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for short-lived spawn protection used by non-respawn companion spawns.
 */
class RecentSpawnProtectionServiceTest {
    @Test
    void storesRecentProtectionAndExpiresOldEntries() {
        RecentSpawnProtectionService service = new RecentSpawnProtectionService(10_000L);
        UUID npcUuid = UUID.randomUUID();

        service.record(npcUuid, "breeding_offspring", "Tamed_Chicken", 1_000L);

        RecentSpawnProtectionService.Protection recent = service.getRecentProtection(npcUuid, 2_000L);
        assertNotNull(recent);
        assertEquals(npcUuid, recent.npcUuid());
        assertEquals("breeding_offspring", recent.branch());
        assertEquals("Tamed_Chicken", recent.roleId());

        assertNull(service.getRecentProtection(npcUuid, 12_001L));
    }
}

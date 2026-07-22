package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionReviveEligibilityServiceTest {
    @Test
    void unavailableAuthorityProtectsAgainstDestructivePermanentRelease() {
        CompanionReviveEligibilityService service = new CompanionReviveEligibilityService();
        UUID npc = UUID.randomUUID();

        assertFalse(service.ready());
        assertFalse(service.supports(npc));
        assertTrue(service.protectsFromPermanentDeath(npc));
    }

    @Test
    void memorySnapshotRemapsAndReleasesWithoutPersistenceReads() {
        CompanionReviveEligibilityService service = new CompanionReviveEligibilityService();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.replace(List.of(new CompanionReviveEligibilityService.Eligibility(
                "profile-dragon", CompanionReviveEligibilityService.Authority.PROVISIONED,
                first)));

        assertTrue(service.ready());
        assertTrue(service.supports(first));
        assertEquals("profile-dragon", service.findByNpc(first).profileId());

        service.remap("profile-dragon", second);
        assertFalse(service.supports(first));
        assertTrue(service.supports(second));

        service.release("profile-dragon");
        assertFalse(service.supports(second));
        assertEquals(null, service.findByProfile("profile-dragon"));
    }

}

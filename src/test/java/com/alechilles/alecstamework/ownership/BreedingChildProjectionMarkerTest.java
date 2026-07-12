package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the durable identity dimensions written before a breeding child enters the store. */
class BreedingChildProjectionMarkerTest {
    @Test
    void markerBindsAttemptChildProfileUuidAndStableProjectionGeneration() {
        UUID planned = UUID.randomUUID();
        TameworkProjectionIdentityComponent marker = BreedingChildProjectionMarker.create(
                "breeding:attempt", "child-0002", "profile-child", planned
        );

        assertEquals(TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                marker.getProjectionKind());
        assertEquals("breeding:attempt", marker.getOperationId());
        assertEquals("child-0002", marker.getSlotKey());
        assertEquals("profile-child", marker.getProfileId());
        assertEquals(planned, marker.getSourceNpcUuid());
        assertEquals(1L, marker.getGeneration());
        assertTrue(BreedingChildProjectionMarker.matches(marker, marker.clone()));
    }

    @Test
    void anotherChildKeyCannotSatisfyTheExpectedMarker() {
        UUID planned = UUID.randomUUID();
        TameworkProjectionIdentityComponent expected = BreedingChildProjectionMarker.create(
                "breeding:attempt", "child-0000", "profile-child", planned
        );
        TameworkProjectionIdentityComponent other = BreedingChildProjectionMarker.create(
                "breeding:attempt", "child-0001", "profile-child", planned
        );

        assertFalse(BreedingChildProjectionMarker.matches(other, expected));
    }
}

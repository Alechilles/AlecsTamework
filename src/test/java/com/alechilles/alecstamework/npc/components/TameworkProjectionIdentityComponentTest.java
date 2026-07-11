package com.alechilles.alecstamework.npc.components;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the restart-visible projection identity marker retains its full operation key. */
class TameworkProjectionIdentityComponentTest {
    @Test
    void clonePreservesRecoveryIdentityAndGeneration() {
        UUID sourceUuid = new UUID(0L, 41L);
        TameworkProjectionIdentityComponent original = new TameworkProjectionIdentityComponent(
                "profile-a",
                "operation-a",
                TameworkProjectionIdentityComponent.KIND_RECOVERY,
                "world:coop:slot-2",
                sourceUuid,
                7L
        );

        TameworkProjectionIdentityComponent clone = original.clone();

        assertNotSame(original, clone);
        assertEquals("profile-a", clone.getProfileId());
        assertEquals("operation-a", clone.getOperationId());
        assertEquals(TameworkProjectionIdentityComponent.KIND_RECOVERY, clone.getProjectionKind());
        assertEquals("world:coop:slot-2", clone.getSlotKey());
        assertEquals(sourceUuid, clone.getSourceNpcUuid());
        assertEquals(7L, clone.getGeneration());
        assertTrue(clone.matches(
                TameworkProjectionIdentityComponent.KIND_RECOVERY,
                "operation-a",
                "profile-a"
        ));
    }

    @Test
    void matchRequiresKindOperationAndProfile() {
        TameworkProjectionIdentityComponent marker = new TameworkProjectionIdentityComponent(
                "profile-a",
                "operation-a",
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                null,
                null,
                3L
        );

        assertFalse(marker.matches(TameworkProjectionIdentityComponent.KIND_RECOVERY,
                "operation-a", "profile-a"));
        assertFalse(marker.matches(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                "operation-b", "profile-a"));
        assertFalse(marker.matches(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                "operation-a", "profile-b"));
    }
}

package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RetirementCommand;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact persistent-marker contract for capture source retirement. */
class HytaleManagedCoopCaptureSourceGatewayTest {
    private static final UUID SOURCE = new UUID(0L, 81L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world-a", 7, 8, 9);

    @Test
    void buildsExactRestartPersistentCaptureSourceMarker() {
        RetirementCommand command = command();

        TameworkProjectionIdentityComponent marker =
                HytaleManagedCoopCaptureSourceGateway.marker(command);

        assertEquals("profile-a", marker.getProfileId());
        assertEquals("capture-a", marker.getOperationId());
        assertEquals(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE,
                marker.getProjectionKind());
        assertEquals(AUTHORITY.slotKey(3), marker.getSlotKey());
        assertEquals(SOURCE, marker.getSourceNpcUuid());
        assertEquals(2L, marker.getGeneration());
        assertTrue(HytaleManagedCoopCaptureSourceGateway.matches(marker, command));
    }

    @Test
    void rejectsAnyConflictingExistingMarkerField() {
        RetirementCommand command = command();
        TameworkProjectionIdentityComponent wrongGeneration =
                HytaleManagedCoopCaptureSourceGateway.marker(command);
        wrongGeneration.setGeneration(1L);
        TameworkProjectionIdentityComponent wrongOperation =
                HytaleManagedCoopCaptureSourceGateway.marker(command);
        wrongOperation.setOperationId("capture-b");

        assertFalse(HytaleManagedCoopCaptureSourceGateway.matches(
                wrongGeneration, command));
        assertFalse(HytaleManagedCoopCaptureSourceGateway.matches(
                wrongOperation, command));
        assertFalse(HytaleManagedCoopCaptureSourceGateway.matches(null, command));
    }

    private RetirementCommand command() {
        return new RetirementCommand(
                SOURCE, "profile-a", "resident-a", "capture-a", AUTHORITY,
                "coop-a", 3, "c".repeat(64), 0L, 2L
        );
    }
}

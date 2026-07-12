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

    /** Regression: released projections previously could commit a slot but fail source retirement. */
    @Test
    void exactFinalizedReleaseMarkerCanTransitionToCaptureSourceMarker() {
        RetirementCommand command = command();
        TameworkProjectionIdentityComponent released =
                new TameworkProjectionIdentityComponent(
                        "profile-a",
                        "managed-coop-release:" + "a".repeat(64),
                        TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                        AUTHORITY.slotKey(3),
                        new UUID(0L, 80L),
                        1L);

        assertTrue(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                released, command));

        released.setSlotKey(AUTHORITY.slotKey(4));
        assertFalse(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                released, command));
        released.setSlotKey(AUTHORITY.slotKey(3));
        released.setSourceNpcUuid(SOURCE);
        assertFalse(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                released, command));
    }

    @Test
    void exactFinalizedImportAdoptionCanTransitionToCaptureSourceMarker() {
        RetirementCommand command = command();
        TameworkProjectionIdentityComponent imported =
                new TameworkProjectionIdentityComponent(
                        "profile-a",
                        "managed-coop-import-operation:" + "b".repeat(64),
                        TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                        AUTHORITY.slotKey(3),
                        SOURCE,
                        0L);

        assertTrue(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                imported, command));

        imported.setSourceNpcUuid(new UUID(0L, 80L));
        assertFalse(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                imported, command));
        imported.setSourceNpcUuid(SOURCE);
        imported.setGeneration(1L);
        assertFalse(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                imported, command));
    }

    @Test
    void exactFinalizedRecoveryCanTransitionButNearMatchesCannot() {
        RetirementCommand command = command();
        TameworkProjectionIdentityComponent recovered =
                new TameworkProjectionIdentityComponent(
                        "profile-a",
                        "11fd5d1a-c328-4dad-8c91-b6a8ca652c97",
                        TameworkProjectionIdentityComponent.KIND_RECOVERY,
                        null,
                        new UUID(0L, 80L),
                        0L);

        assertTrue(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                recovered, command));

        recovered.setOperationId("11FD5D1A-C328-4DAD-8C91-B6A8CA652C97");
        assertFalse(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                recovered, command));
        recovered.setOperationId("11fd5d1a-c328-4dad-8c91-b6a8ca652c97");
        recovered.setSlotKey(AUTHORITY.slotKey(3));
        assertFalse(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                recovered, command));
        recovered.setSlotKey(null);
        recovered.setSourceNpcUuid(SOURCE);
        assertFalse(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                recovered, command));
        recovered.setSourceNpcUuid(new UUID(0L, 80L));
        recovered.setGeneration(1L);
        assertFalse(HytaleManagedCoopCaptureSourceGateway.canTransitionFinalizedProjection(
                recovered, command));
    }

    private RetirementCommand command() {
        return new RetirementCommand(
                SOURCE, "profile-a", "resident-a", "capture-a", AUTHORITY,
                "coop-a", 3, "c".repeat(64), 0L, 2L
        );
    }
}

package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for operation-owned projection profile persistence. */
class CommandLinkedNpcStateSnapshotServiceTest {
    @Test
    void recoveryAndManagedReleaseMarkersDeferGenericProfileUpsert() {
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_RECOVERY
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE
        )));
    }

    @Test
    void missingIncompleteAndUnknownMarkersDoNotSuppressNormalPersistence() {
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(null));
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "", TameworkProjectionIdentityComponent.KIND_RECOVERY,
                        null, null, 0L
                )
        ));
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "operation-a", "UNKNOWN", null, null, 0L
                )
        ));
    }

    private TameworkProjectionIdentityComponent marker(String kind) {
        return new TameworkProjectionIdentityComponent(
                "profile-a", "operation-a", kind, null, null, 0L
        );
    }
}

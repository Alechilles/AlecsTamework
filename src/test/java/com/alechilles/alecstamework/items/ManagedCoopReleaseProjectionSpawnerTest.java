package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for classifying Hytale store rejection after managed-coop release spawn. */
class ManagedCoopReleaseProjectionSpawnerTest {
    @Test
    void failedRoleComponentIsDistinguishedFromIdentityRejection() {
        assertEquals(
                "managed_release_role_build_failed_component",
                ManagedCoopReleaseProjectionSpawner.rejectionDetail(
                        new ManagedCoopReleaseProjectionSpawner.RejectedSpawnEvidence(
                                true, false, false, false, false))
        );
    }

    @Test
    void validRoleAndExactIdentityRetainStoreRejectionEvidence() {
        assertEquals(
                "managed_release_store_rejected_with_exact_identity",
                ManagedCoopReleaseProjectionSpawner.rejectionDetail(
                        new ManagedCoopReleaseProjectionSpawner.RejectedSpawnEvidence(
                                false, true, false, true, true))
        );
    }

    @Test
    void despawningProjectionIsDistinguishedFromOrdinaryStoreRejection() {
        assertEquals(
                "managed_release_store_rejected_despawning_with_exact_identity",
                ManagedCoopReleaseProjectionSpawner.rejectionDetail(
                        new ManagedCoopReleaseProjectionSpawner.RejectedSpawnEvidence(
                                false, true, true, true, true))
        );
    }

    @Test
    void missingMarkerIsReportedSeparatelyFromMissingPlannedUuid() {
        assertEquals(
                "managed_release_store_rejected_projection_marker_missing",
                ManagedCoopReleaseProjectionSpawner.rejectionDetail(
                        new ManagedCoopReleaseProjectionSpawner.RejectedSpawnEvidence(
                                false, true, false, true, false))
        );
        assertEquals(
                "managed_release_store_rejected_planned_uuid_missing",
                ManagedCoopReleaseProjectionSpawner.rejectionDetail(
                        new ManagedCoopReleaseProjectionSpawner.RejectedSpawnEvidence(
                                false, true, false, false, true))
        );
    }
}

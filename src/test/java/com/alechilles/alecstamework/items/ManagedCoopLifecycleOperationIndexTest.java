package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime contract for fail-closed atomic lifecycle-operation projections. */
class ManagedCoopLifecycleOperationIndexTest {
    private static final ManagedCoopAuthorityKey COOP_A =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final ManagedCoopAuthorityKey COOP_B =
            new ManagedCoopAuthorityKey("world", 4, 5, 6);
    private static final String HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void completeSnapshotCreatesImmutableDeterministicAliasLookups() {
        ManagedCoopLifecycleOperationIndex index = new ManagedCoopLifecycleOperationIndex();
        OperationRecord capture = capture(
                "capture-a", "profile-a", COOP_A, 0, uuid(1),
                OperationState.SOURCE_RETIRE_REQUESTED, 2L
        );
        OperationRecord release = release(
                "release-b", "profile-b", COOP_B, 1, uuid(2), uuid(2),
                OperationState.PROJECTION_CREATED, 2L
        );
        ArrayList<OperationRecord> source = new ArrayList<>(List.of(release, capture));

        ManagedCoopLifecycleOperationIndex.RebuildResult result =
                index.rebuild(ManagedCoopReadResult.loaded(source));
        ManagedCoopLifecycleOperationIndex.Snapshot snapshot = index.snapshot();
        source.clear();

        assertTrue(result.rebuilt());
        assertTrue(index.isTrusted());
        assertTrue(snapshot.trusted());
        assertEquals(1L, snapshot.revision());
        assertEquals(List.of(capture, release), snapshot.operations());
        assertSame(capture, snapshot.operationById("capture-a"));
        assertSame(capture, snapshot.operationByProfile("profile-a"));
        assertSame(capture, snapshot.operationAt(COOP_A, 0));
        assertSame(capture, snapshot.operationByUuid(uuid(1)));
        assertSame(release, snapshot.operationByUuid(uuid(2)));
        assertNull(snapshot.operationAt(COOP_A, -1));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.operations().clear());
    }

    @Test
    void failedReadRetainsEvidenceAtSameRevisionAndMarksItUntrusted() {
        ManagedCoopLifecycleOperationIndex index = new ManagedCoopLifecycleOperationIndex();
        OperationRecord original = capture(
                "capture-a", "profile-a", COOP_A, 0, uuid(1),
                OperationState.SLOT_COMMITTED, 1L
        );
        assertTrue(index.rebuild(ManagedCoopReadResult.loaded(List.of(original))).rebuilt());
        ManagedCoopLifecycleOperationIndex.Snapshot trusted = index.snapshot();

        ManagedCoopLifecycleOperationIndex.RebuildResult rejected = index.rebuild(
                ManagedCoopReadResult.sqlFailure(new SQLException("database busy"))
        );
        ManagedCoopLifecycleOperationIndex.Snapshot retained = index.snapshot();

        assertFalse(rejected.rebuilt());
        assertEquals(1L, retained.revision());
        assertFalse(retained.trusted());
        assertFalse(index.isTrusted());
        assertSame(original, retained.operationById("capture-a"));
        assertTrue(trusted.trusted(), "a retained point-in-time snapshot remains immutable");

        assertTrue(index.rebuild(ManagedCoopReadResult.loaded(List.of())).rebuilt());
        assertEquals(2L, index.snapshot().revision());
        assertTrue(index.isTrusted());
        assertNull(index.operationById("capture-a"));
    }

    @Test
    void duplicateAssignmentsAreRejectedWithoutPublishingPartialMappings() {
        OperationRecord first = capture(
                "capture-a", "profile-a", COOP_A, 0, uuid(1),
                OperationState.PREPARED, 0L
        );

        assertRejected(List.of(first, capture(
                "capture-a", "profile-b", COOP_B, 1, uuid(2),
                OperationState.PREPARED, 0L)), "duplicate_operation_id:");
        assertRejected(List.of(first, capture(
                "capture-b", "profile-a", COOP_B, 1, uuid(2),
                OperationState.PREPARED, 0L)), "duplicate_operation_profile:");
        assertRejected(List.of(first, capture(
                "capture-b", "profile-b", COOP_A, 0, uuid(2),
                OperationState.PREPARED, 0L)), "duplicate_operation_slot:");
        assertRejected(List.of(first, capture(
                "capture-b", "profile-b", COOP_B, 1, uuid(1),
                OperationState.PREPARED, 0L)), "duplicate_coop_lifecycle_uuid:");
    }

    @Test
    void terminalGenerationAndUuidShapeMismatchesFailClosed() {
        ArrayList<OperationRecord> invalid = new ArrayList<>();
        invalid.add(operation("inactive", OperationKind.CAPTURE, "profile-inactive", COOP_A, 0,
                uuid(1), null, null, OperationState.PREPARED, 0L, false, 0L));
        invalid.add(operation("terminal", OperationKind.CAPTURE, "profile-terminal", COOP_A, 0,
                uuid(1), null, null, OperationState.COMPLETE, 3L, true, -1L));
        invalid.add(operation("generation", OperationKind.CAPTURE, "profile-generation", COOP_A, 0,
                uuid(1), null, null, OperationState.SLOT_COMMITTED, 0L, true, 0L));
        invalid.add(operation("capture-shape", OperationKind.CAPTURE, "profile-capture-shape", COOP_A, 0,
                uuid(1), uuid(2), null, OperationState.PREPARED, 0L, true, 0L));
        invalid.add(operation("release-source", OperationKind.RELEASE, "profile-release-source", COOP_A, 0,
                uuid(1), uuid(2), null, OperationState.PREPARED, 0L, true, 0L));
        invalid.add(operation("release-early-actual", OperationKind.RELEASE,
                "profile-release-early-actual", COOP_A, 0, null, uuid(2), uuid(3),
                OperationState.SPAWN_CLAIMED, 1L, true, 0L));
        invalid.add(operation("release-missing-actual", OperationKind.RELEASE,
                "profile-release-missing-actual", COOP_A, 0, null, uuid(2), null,
                OperationState.PROJECTION_CREATED, 2L, true, 0L));
        invalid.add(operation("release-target-mismatch", OperationKind.RELEASE,
                "profile-release-target-mismatch", COOP_A, 0, null, uuid(2), uuid(3),
                OperationState.PROJECTION_CREATED, 2L, true, 0L));
        invalid.add(operation("unsupported-kind", OperationKind.IMPORT, "profile-import", COOP_A, 0,
                null, uuid(2), null, OperationState.PREPARED, 0L, true, 0L));
        invalid.add(operation("nil-source", OperationKind.CAPTURE, "profile-nil", COOP_A, 0,
                new UUID(0L, 0L), null, null, OperationState.PREPARED, 0L, true, 0L));

        for (OperationRecord operation : invalid) {
            ManagedCoopLifecycleOperationIndex index = new ManagedCoopLifecycleOperationIndex();
            ManagedCoopLifecycleOperationIndex.RebuildResult result =
                    index.rebuild(ManagedCoopReadResult.loaded(List.of(operation)));
            assertFalse(result.rebuilt(), operation.operationId());
            assertFalse(index.isTrusted(), operation.operationId());
            assertEquals(0L, index.snapshot().revision(), operation.operationId());
            assertTrue(index.snapshot().operations().isEmpty(), operation.operationId());
        }
    }

    private static void assertRejected(List<OperationRecord> operations, String detailPrefix) {
        ManagedCoopLifecycleOperationIndex index = new ManagedCoopLifecycleOperationIndex();

        ManagedCoopLifecycleOperationIndex.RebuildResult result =
                index.rebuild(ManagedCoopReadResult.loaded(operations));

        assertFalse(result.rebuilt());
        assertTrue(result.detail().startsWith(detailPrefix), result.detail());
        assertEquals(0L, index.snapshot().revision());
        assertTrue(index.snapshot().operations().isEmpty());
    }

    private static OperationRecord capture(String operationId,
                                           String profileId,
                                           ManagedCoopAuthorityKey key,
                                           int slot,
                                           UUID sourceUuid,
                                           OperationState state,
                                           long generation) {
        return operation(operationId, OperationKind.CAPTURE, profileId, key, slot,
                sourceUuid, null, null, state, generation, true, 0L);
    }

    private static OperationRecord release(String operationId,
                                           String profileId,
                                           ManagedCoopAuthorityKey key,
                                           int slot,
                                           UUID plannedUuid,
                                           UUID actualUuid,
                                           OperationState state,
                                           long generation) {
        return operation(operationId, OperationKind.RELEASE, profileId, key, slot,
                null, plannedUuid, actualUuid, state, generation, true, 0L);
    }

    private static OperationRecord operation(String operationId,
                                             OperationKind kind,
                                             String profileId,
                                             ManagedCoopAuthorityKey key,
                                             int slot,
                                             UUID sourceUuid,
                                             UUID plannedUuid,
                                             UUID actualUuid,
                                             OperationState state,
                                             long generation,
                                             boolean active,
                                             long completedAtMs) {
        return new OperationRecord(
                operationId, kind, profileId, key, "coop_chicken", slot,
                sourceUuid, plannedUuid, actualUuid, state, HASH,
                0L, generation, 0, active, -100L, -90L, completedAtMs, null
        );
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }
}

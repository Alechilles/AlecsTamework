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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers explicit, typed, fail-closed operation-index refresh boundaries. */
class ManagedCoopLifecycleOperationIndexRefreshServiceTest {
    private static final ManagedCoopAuthorityKey COOP =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);
    private static final String HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void completeTypedReadPublishesOneTrustedRevisionWithoutWarnings() {
        ManagedCoopLifecycleOperationIndex index = new ManagedCoopLifecycleOperationIndex();
        OperationRecord operation = capture();
        MutableSource source = new MutableSource(ManagedCoopReadResult.loaded(List.of(operation)));
        ArrayList<String> warnings = new ArrayList<>();
        ManagedCoopLifecycleOperationIndexRefreshService service =
                new ManagedCoopLifecycleOperationIndexRefreshService(index, source, warnings::add);

        ManagedCoopLifecycleOperationIndexRefreshService.RefreshResult result = service.refresh();

        assertTrue(result.refreshed());
        assertEquals(1L, result.revision());
        assertTrue(index.isTrusted());
        assertSame(operation, index.operationByProfile("profile-a"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void typedFailureRetainsEvidenceMarksItUntrustedAndLogsExactlyOnce() {
        ManagedCoopLifecycleOperationIndex index = new ManagedCoopLifecycleOperationIndex();
        OperationRecord operation = capture();
        MutableSource source = new MutableSource(ManagedCoopReadResult.loaded(List.of(operation)));
        ArrayList<String> warnings = new ArrayList<>();
        ManagedCoopLifecycleOperationIndexRefreshService service =
                new ManagedCoopLifecycleOperationIndexRefreshService(index, source, warnings::add);
        service.refresh();

        source.result = ManagedCoopReadResult.sqlFailure(new SQLException("database busy"));
        ManagedCoopLifecycleOperationIndexRefreshService.RefreshResult rejected = service.refresh();

        assertFalse(rejected.refreshed());
        assertEquals(1L, rejected.revision());
        assertEquals("operations:sql_error:database busy", rejected.detail());
        assertFalse(index.isTrusted());
        assertSame(operation, index.operationById("capture-a"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains(rejected.detail()));
    }

    @Test
    void missingResultAndSourceExceptionEachReturnRejectedTypedOutcome() {
        ManagedCoopLifecycleOperationIndex missingIndex =
                new ManagedCoopLifecycleOperationIndex();
        MutableSource missingSource = new MutableSource(null);
        ArrayList<String> missingWarnings = new ArrayList<>();
        ManagedCoopLifecycleOperationIndexRefreshService missingService =
                new ManagedCoopLifecycleOperationIndexRefreshService(
                        missingIndex, missingSource, missingWarnings::add);

        ManagedCoopLifecycleOperationIndexRefreshService.RefreshResult missing =
                missingService.refresh();

        assertFalse(missing.refreshed());
        assertEquals("operations:missing_read_result", missing.detail());
        assertEquals(1, missingWarnings.size());

        ManagedCoopLifecycleOperationIndex throwingIndex =
                new ManagedCoopLifecycleOperationIndex();
        ArrayList<String> throwingWarnings = new ArrayList<>();
        ManagedCoopLifecycleOperationIndexRefreshService throwingService =
                new ManagedCoopLifecycleOperationIndexRefreshService(
                        throwingIndex,
                        () -> { throw new IllegalStateException("snapshot source failed"); },
                        throwingWarnings::add
                );

        ManagedCoopLifecycleOperationIndexRefreshService.RefreshResult sourceFailure =
                throwingService.refresh();

        assertFalse(sourceFailure.refreshed());
        assertEquals("operations:source_exception:snapshot source failed", sourceFailure.detail());
        assertEquals(1, throwingWarnings.size());
        assertFalse(throwingIndex.isTrusted());
    }

    @Test
    void invalidLoadedCandidateLogsOneRebuildReasonWithoutAdvancingRevision() {
        ManagedCoopLifecycleOperationIndex index = new ManagedCoopLifecycleOperationIndex();
        OperationRecord terminal = new OperationRecord(
                "capture-terminal", OperationKind.CAPTURE, "profile-a", COOP,
                "coop_chicken", 0, uuid(1), null, null, OperationState.COMPLETE,
                HASH, 0L, 3L, 0, true, -100L, -90L, -80L, null
        );
        MutableSource source = new MutableSource(ManagedCoopReadResult.loaded(List.of(terminal)));
        ArrayList<String> warnings = new ArrayList<>();
        ManagedCoopLifecycleOperationIndexRefreshService service =
                new ManagedCoopLifecycleOperationIndexRefreshService(index, source, warnings::add);

        ManagedCoopLifecycleOperationIndexRefreshService.RefreshResult result = service.refresh();

        assertFalse(result.refreshed());
        assertEquals(0L, result.revision());
        assertTrue(result.detail().startsWith("terminal_or_inactive_coop_lifecycle_operation:"));
        assertEquals(1, warnings.size());
    }

    private static OperationRecord capture() {
        return new OperationRecord(
                "capture-a", OperationKind.CAPTURE, "profile-a", COOP,
                "coop_chicken", 0, uuid(1), null, null, OperationState.SLOT_COMMITTED,
                HASH, 0L, 1L, 0, true, -100L, -90L, 0L, null
        );
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }

    private static final class MutableSource
            implements ManagedCoopLifecycleOperationIndexRefreshService.SnapshotSource {
        private ManagedCoopReadResult<List<OperationRecord>> result;

        private MutableSource(ManagedCoopReadResult<List<OperationRecord>> result) {
            this.result = result;
        }

        @Override
        public ManagedCoopReadResult<List<OperationRecord>> loadOperations() {
            return result;
        }
    }
}

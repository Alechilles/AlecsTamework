package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService.ComponentResult;
import static com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService.ComponentStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for keeping the two managed-coop runtime projections in one trust domain. */
class ManagedCoopCompositeIndexRefreshServiceTest {
    @Test
    void bothCompleteRefreshesPublishOneSuccessfulAggregate() {
        Scenario scenario = scenario();

        ManagedCoopCompositeIndexRefreshService.RefreshResult result =
                scenario.composite.refresh();

        assertTrue(result.refreshed());
        assertEquals(1L, result.residentRevision());
        assertEquals(1L, result.operationRevision());
        assertEquals(ComponentStatus.REFRESHED, result.residentResult().status());
        assertEquals(ComponentStatus.REFRESHED, result.operationResult().status());
        assertNull(result.detail());
        assertTrue(scenario.residentIndex.isTrusted());
        assertTrue(scenario.operationIndex.isTrusted());
        assertTrue(scenario.composite.isTrusted());
        assertEquals(1, scenario.residentSource.refreshCount);
        assertEquals(1, scenario.operationSource.refreshCount);
    }

    @Test
    void residentFailureStillRefreshesOperationsThenRevokesBothTrusts() {
        Scenario scenario = scenario();
        assertTrue(scenario.composite.refresh().refreshed());
        scenario.residentSource.authorities =
                ManagedCoopReadResult.sqlFailure(new SQLException("resident database busy"));

        ManagedCoopCompositeIndexRefreshService.RefreshResult result =
                scenario.composite.refresh();

        assertFalse(result.refreshed());
        assertEquals(ComponentStatus.REJECTED, result.residentResult().status());
        assertEquals(ComponentStatus.REFRESHED, result.operationResult().status());
        assertEquals(1L, result.residentRevision());
        assertEquals(2L, result.operationRevision());
        assertTrue(result.detail().contains("resident:rejected:authorities:sql_error:"));
        assertFalse(scenario.residentIndex.isTrusted());
        assertFalse(scenario.operationIndex.isTrusted());
        assertFalse(scenario.composite.isTrusted());
        assertEquals(2, scenario.residentSource.refreshCount);
        assertEquals(2, scenario.operationSource.refreshCount);
    }

    @Test
    void operationFailureStillRefreshesResidentsThenRevokesBothTrusts() {
        Scenario scenario = scenario();
        assertTrue(scenario.composite.refresh().refreshed());
        scenario.operationSource.operations = ManagedCoopReadResult.integrityFailure(
                new IllegalStateException("corrupt operation row")
        );

        ManagedCoopCompositeIndexRefreshService.RefreshResult result =
                scenario.composite.refresh();

        assertFalse(result.refreshed());
        assertEquals(ComponentStatus.REFRESHED, result.residentResult().status());
        assertEquals(ComponentStatus.REJECTED, result.operationResult().status());
        assertEquals(2L, result.residentRevision());
        assertEquals(1L, result.operationRevision());
        assertTrue(result.detail().contains("operations:rejected:operations:integrity_violation:"));
        assertFalse(scenario.residentIndex.isTrusted());
        assertFalse(scenario.operationIndex.isTrusted());
        assertFalse(scenario.composite.isTrusted());
        assertEquals(2, scenario.residentSource.refreshCount);
        assertEquals(2, scenario.operationSource.refreshCount);
    }

    @Test
    void exceptionInEitherAttemptDoesNotSkipPeerAndRevokesStaleTrust() {
        assertExceptionScenario(true);
        assertExceptionScenario(false);
    }

    @Test
    void rejectedActionCannotLeavePreexistingSnapshotsTrusted() {
        ManagedCoopResidentIndex residentIndex = trustedResidentIndex();
        ManagedCoopLifecycleOperationIndex operationIndex = trustedOperationIndex();
        ManagedCoopCompositeIndexRefreshService composite =
                new ManagedCoopCompositeIndexRefreshService(
                        residentIndex,
                        operationIndex,
                        () -> new ComponentResult(ComponentStatus.REJECTED, 1L, "early_failure"),
                        () -> new ComponentResult(ComponentStatus.REFRESHED, 1L, null)
                );

        ManagedCoopCompositeIndexRefreshService.RefreshResult result = composite.refresh();

        assertFalse(result.refreshed());
        assertEquals(1L, residentIndex.snapshot().revision());
        assertEquals(1L, operationIndex.snapshot().revision());
        assertFalse(residentIndex.isTrusted());
        assertFalse(operationIndex.isTrusted());
        assertFalse(composite.isTrusted());
    }

    private static void assertExceptionScenario(boolean residentThrows) {
        ManagedCoopResidentIndex residentIndex = trustedResidentIndex();
        ManagedCoopLifecycleOperationIndex operationIndex = trustedOperationIndex();
        AtomicInteger residentCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        ManagedCoopCompositeIndexRefreshService.RefreshAction residentAction = () -> {
            residentCalls.incrementAndGet();
            if (residentThrows) {
                throw new IllegalStateException("resident boom");
            }
            return new ComponentResult(ComponentStatus.REFRESHED, 1L, null);
        };
        ManagedCoopCompositeIndexRefreshService.RefreshAction operationAction = () -> {
            operationCalls.incrementAndGet();
            if (!residentThrows) {
                throw new IllegalStateException("operation boom");
            }
            return new ComponentResult(ComponentStatus.REFRESHED, 1L, null);
        };
        ManagedCoopCompositeIndexRefreshService composite =
                new ManagedCoopCompositeIndexRefreshService(
                        residentIndex, operationIndex, residentAction, operationAction);

        ManagedCoopCompositeIndexRefreshService.RefreshResult result = composite.refresh();

        assertFalse(result.refreshed());
        assertEquals(1, residentCalls.get());
        assertEquals(1, operationCalls.get());
        assertEquals(
                residentThrows ? ComponentStatus.EXCEPTION : ComponentStatus.REFRESHED,
                result.residentResult().status()
        );
        assertEquals(
                residentThrows ? ComponentStatus.REFRESHED : ComponentStatus.EXCEPTION,
                result.operationResult().status()
        );
        assertFalse(residentIndex.isTrusted());
        assertFalse(operationIndex.isTrusted());
        assertFalse(composite.isTrusted());
        assertEquals(1L, residentIndex.snapshot().revision());
        assertEquals(1L, operationIndex.snapshot().revision());
    }

    private static Scenario scenario() {
        ManagedCoopResidentIndex residentIndex = new ManagedCoopResidentIndex();
        ManagedCoopLifecycleOperationIndex operationIndex =
                new ManagedCoopLifecycleOperationIndex();
        MutableResidentSource residentSource = new MutableResidentSource();
        MutableOperationSource operationSource = new MutableOperationSource();
        ManagedCoopResidentIndexRefreshService residentRefresh =
                new ManagedCoopResidentIndexRefreshService(
                        residentIndex, residentSource, ignored -> { });
        ManagedCoopLifecycleOperationIndexRefreshService operationRefresh =
                new ManagedCoopLifecycleOperationIndexRefreshService(
                        operationIndex, operationSource, ignored -> { });
        ManagedCoopCompositeIndexRefreshService composite =
                new ManagedCoopCompositeIndexRefreshService(
                        residentRefresh, operationRefresh, residentIndex, operationIndex);
        return new Scenario(
                residentIndex, operationIndex, residentSource, operationSource, composite);
    }

    private static ManagedCoopResidentIndex trustedResidentIndex() {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        assertTrue(index.rebuild(
                ManagedCoopReadResult.loaded(List.of()),
                ManagedCoopReadResult.loaded(List.of())
        ).rebuilt());
        return index;
    }

    private static ManagedCoopLifecycleOperationIndex trustedOperationIndex() {
        ManagedCoopLifecycleOperationIndex index =
                new ManagedCoopLifecycleOperationIndex();
        assertTrue(index.rebuild(ManagedCoopReadResult.loaded(List.of())).rebuilt());
        return index;
    }

    private record Scenario(
            ManagedCoopResidentIndex residentIndex,
            ManagedCoopLifecycleOperationIndex operationIndex,
            MutableResidentSource residentSource,
            MutableOperationSource operationSource,
            ManagedCoopCompositeIndexRefreshService composite) {
    }

    private static final class MutableResidentSource
            implements ManagedCoopResidentIndexRefreshService.SnapshotSource {
        private ManagedCoopReadResult<List<AuthorityRecord>> authorities =
                ManagedCoopReadResult.loaded(List.of());
        private ManagedCoopReadResult<List<ResidentRecord>> residents =
                ManagedCoopReadResult.loaded(List.of());
        private int refreshCount;

        @Override
        public ManagedCoopReadResult<List<AuthorityRecord>> loadAuthorities() {
            refreshCount++;
            return authorities;
        }

        @Override
        public ManagedCoopReadResult<List<ResidentRecord>> loadResidents() {
            return residents;
        }
    }

    private static final class MutableOperationSource
            implements ManagedCoopLifecycleOperationIndexRefreshService.SnapshotSource {
        private ManagedCoopReadResult<List<OperationRecord>> operations =
                ManagedCoopReadResult.loaded(List.of());
        private int refreshCount;

        @Override
        public ManagedCoopReadResult<List<OperationRecord>> loadOperations() {
            refreshCount++;
            return operations;
        }
    }
}

package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDisposition;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncident;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentSeverity;
import com.alechilles.alecstamework.persistence.incidents.PersistenceIncidentStatus;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineState;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReconciliationOperationRecoveryVerifierTest {
    @Test
    void terminalCommittedOperationClearsItsExactOperationAndProfileFences() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        ReconciliationOperationRecoveryVerifier verifier =
                new ReconciliationOperationRecoveryVerifier(
                        ignored -> operation(CompanionPopulationOperationRecord.State.COMMITTED),
                        reads::incrementAndGet,
                        publications::incrementAndGet
                );

        ScopedRecoveryVerification result = verifier.verify(context("profile-a"));

        assertEquals(ScopedRecoveryResolution.RESOLVED_NEW_STATE, result.resolution());
        assertEquals("operation-hash", result.evidenceHashes().get("operation-fence"));
        assertEquals("profile-hash", result.evidenceHashes().get("profile-fence"));
        assertEquals(1, reads.get());
        assertNotNull(result.indexPublisher());
        result.indexPublisher().publish();
        assertEquals(1, publications.get());
    }

    @Test
    void nonterminalOperationRetainsItsFences() {
        ReconciliationOperationRecoveryVerifier verifier =
                new ReconciliationOperationRecoveryVerifier(
                        ignored -> {
                            throw new IllegalStateException("not terminal");
                        },
                        () -> { },
                        () -> { }
                );

        ScopedRecoveryVerification result = verifier.verify(context("profile-a"));

        assertEquals(ScopedRecoveryResolution.STILL_AMBIGUOUS, result.resolution());
        assertEquals("reconciliation_operation_not_terminal", result.resolutionCode());
    }

    @Test
    void mismatchedProfileFenceCannotBeClearedByAnotherTerminalOperation() {
        ReconciliationOperationRecoveryVerifier verifier =
                new ReconciliationOperationRecoveryVerifier(
                        ignored -> operation(CompanionPopulationOperationRecord.State.COMMITTED),
                        () -> { },
                        () -> { }
                );

        ScopedRecoveryVerification result = verifier.verify(context("profile-b"));

        assertEquals(ScopedRecoveryResolution.CONTRADICTORY_EVIDENCE, result.resolution());
    }

    private static CompanionPopulationOperationRecord operation(
            CompanionPopulationOperationRecord.State state
    ) {
        return new CompanionPopulationOperationRecord(
                "operation-a", "profile-a", "LIFECYCLE_CHANGE", state, 12L,
                "{}", "{}", null, 1L, 2L, state.isTerminal() ? 2L : 0L, null
        );
    }

    private static ScopedRecoveryContext context(String profileId) {
        PersistenceIncident incident = new PersistenceIncident(
                "incident-a", "fingerprint", PersistenceIncidentStatus.OPEN,
                PersistenceIncidentSeverity.ERROR,
                PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY,
                PersistenceDisposition.SCOPED_QUARANTINE,
                PersistenceDomain.RECONCILIATION,
                PersistenceOperationPhase.RECOVERY,
                "operation_recovery_target_not_observed",
                "operation-a", "boot-a", 1L, 1L, 0L, 1L, 0L,
                null, null, "{}", null, null
        );
        PersistenceQuarantineRecord operationFence = quarantine(
                "operation-fence",
                new PersistenceScope(
                        PersistenceScopeType.OPERATION, "operation-a", "scope-operation", null
                ),
                "operation-hash"
        );
        PersistenceQuarantineRecord profileFence = quarantine(
                "profile-fence",
                new PersistenceScope(
                        PersistenceScopeType.PROFILE, profileId, "scope-profile", null
                ),
                "profile-hash"
        );
        return new ScopedRecoveryContext(
                incident,
                List.of(operationFence, profileFence),
                ScopedRecoveryTrigger.BOUNDED_RETRY
        );
    }

    private static PersistenceQuarantineRecord quarantine(
            String id,
            PersistenceScope scope,
            String evidenceHash
    ) {
        return new PersistenceQuarantineRecord(
                id, "incident-a", scope, PersistenceDomain.RECONCILIATION,
                "operation_recovery_target_not_observed",
                PersistenceQuarantineState.ACTIVE, evidenceHash, 0L,
                1L, 1L, 0L, null
        );
    }
}

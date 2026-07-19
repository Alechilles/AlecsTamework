package com.alechilles.alecstamework.persistence.incidents;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceFailureClassifierTest {
    private final PersistenceFailureClassifier classifier = new PersistenceFailureClassifier();

    @Test
    void confirmedRollbackDomainConflictKeepsStorageHealthy() {
        PersistenceFailureClassification result = classifier.classify(context(
                PersistenceTransactionOutcome.ROLLED_BACK, true, true, false, false));

        assertEquals(PersistenceFailureClass.ROLLED_BACK_DOMAIN_CONFLICT, result.failureClass());
        assertEquals(PersistenceDisposition.DOMAIN_REJECTION, result.disposition());
        assertFalse(result.storageAuthorityLost());
    }

    @Test
    void postCommitPublicationFailureUsesDurableScopedQuarantine() {
        PersistenceFailureClassification result = classifier.classify(context(
                PersistenceTransactionOutcome.COMMITTED, true, true, false, false));

        assertEquals(PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE, result.failureClass());
        assertEquals(PersistenceDisposition.SCOPED_QUARANTINE, result.disposition());
        assertFalse(result.storageAuthorityLost());
    }

    @Test
    void boundedAmbiguityWithoutDurableFenceEscalatesGlobally() {
        PersistenceFailureClassification result = classifier.classify(context(
                PersistenceTransactionOutcome.ROLLED_BACK, false, true, true, false));

        assertEquals(PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY, result.failureClass());
        assertEquals(PersistenceDisposition.GLOBAL_READ_ONLY, result.disposition());
        assertTrue(result.storageAuthorityLost());
    }

    @Test
    void unknownCommitOutcomeAlwaysEntersGlobalReadOnly() {
        PersistenceFailureClassification result = classifier.classify(context(
                PersistenceTransactionOutcome.UNKNOWN, true, true, false, false));

        assertEquals(PersistenceFailureClass.UNKNOWN_TRANSACTION_OUTCOME, result.failureClass());
        assertEquals(PersistenceDisposition.GLOBAL_READ_ONLY, result.disposition());
        assertTrue(result.storageAuthorityLost());
    }

    @Test
    void missingCoverageIsAnAuthorityDenialNotStorageFailure() {
        PersistenceFailureClassification result = classifier.classify(context(
                PersistenceTransactionOutcome.NOT_STARTED, false, false, false, true));

        assertEquals(PersistenceFailureClass.COVERAGE_UNAVAILABLE, result.failureClass());
        assertEquals(PersistenceDisposition.AUTHORITY_NOT_READY, result.disposition());
        assertFalse(result.storageAuthorityLost());
    }

    private PersistenceFailureContext context(PersistenceTransactionOutcome transaction,
                                              boolean durableFence,
                                              boolean canonicalReadable,
                                              boolean liveVisible,
                                              boolean coverageUnavailable) {
        return new PersistenceFailureContext(
                "test_reason",
                PersistenceDomain.MANAGED_COOP_RELEASE,
                PersistenceOperationPhase.COMMIT,
                transaction,
                List.of(new PersistenceScope(PersistenceScopeType.PROFILE, "profile-a", "hash-a", null)),
                durableFence,
                canonicalReadable,
                false,
                false,
                false,
                false,
                coverageUnavailable,
                liveVisible,
                "operation-a",
                null
        );
    }
}

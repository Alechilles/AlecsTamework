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
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.testing.DeterministicPersistenceFaultInjector;
import com.alechilles.alecstamework.persistence.testing.DeterministicPersistenceFaultInjector.FaultMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostCommitPublicationRecoveryVerifierTest {
    @Test
    void committedPublicationFailureRequiresReadbackAndCarriesExactFenceEvidence() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        PostCommitPublicationRecoveryVerifier verifier = verifier(
                probes::incrementAndGet, publications::incrementAndGet);
        PersistenceQuarantineRecord fence = fence(PersistenceDomain.OWNER_MUTATION);

        ScopedRecoveryVerification result = verifier.verify(context(
                incident(PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE), fence));

        assertEquals(ScopedRecoveryResolution.RESOLVED_NEW_STATE, result.resolution());
        assertEquals("committed_state_readback_verified", result.resolutionCode());
        assertEquals(fence.evidenceHash(), result.evidenceHashes().get(fence.quarantineId()));
        assertEquals(1, probes.get());
        assertEquals(0, publications.get());
        assertNotNull(result.indexPublisher());
        result.indexPublisher().publish();
        assertEquals(1, publications.get());
    }

    @Test
    void applyAmbiguityCannotBeClearedByCanonicalPublicationVerifier() {
        AtomicInteger probes = new AtomicInteger();
        PostCommitPublicationRecoveryVerifier verifier = verifier(probes::incrementAndGet, () -> { });

        ScopedRecoveryVerification result = verifier.verify(context(
                incident(PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY),
                fence(PersistenceDomain.OWNER_MUTATION)));

        assertEquals(ScopedRecoveryResolution.STILL_AMBIGUOUS, result.resolution());
        assertEquals("domain_evidence_verifier_required", result.resolutionCode());
        assertEquals(0, probes.get());
    }

    @Test
    void unreadableAuthorityRetainsFenceWithoutPublisher() {
        IllegalStateException failure = new IllegalStateException("catalog unavailable");
        PostCommitPublicationRecoveryVerifier verifier = verifier(() -> {
            throw failure;
        }, () -> { });

        ScopedRecoveryVerification result = verifier.verify(context(
                incident(PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE),
                fence(PersistenceDomain.OWNER_MUTATION)));

        assertEquals(ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE, result.resolution());
        assertEquals("canonical_readback_unavailable", result.resolutionCode());
        assertSame(failure, result.failure());
        assertEquals(null, result.indexPublisher());
    }

    @Test
    void mismatchedFenceDomainIsContradictory() {
        PostCommitPublicationRecoveryVerifier verifier = verifier(() -> { }, () -> { });

        ScopedRecoveryVerification result = verifier.verify(context(
                incident(PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE),
                fence(PersistenceDomain.MANAGED_COOP_RELEASE)));

        assertEquals(ScopedRecoveryResolution.CONTRADICTORY_EVIDENCE, result.resolution());
        assertEquals("quarantine_domain_mismatch", result.resolutionCode());
    }

    @Test
    void injectedReadbackAndPublicationBoundariesNeverClearOptimistically() throws Exception {
        DeterministicPersistenceFaultInjector readbackFault = new DeterministicPersistenceFaultInjector()
                .arm("readback-boundary", PersistenceCheckpoint.AFTER_CANONICAL_READBACK,
                        FaultMode.DELAYED_OR_MISSING_EVIDENCE, 1);
        PostCommitPublicationRecoveryVerifier unavailable = new PostCommitPublicationRecoveryVerifier(
                PersistenceDomain.OWNER_MUTATION, "owner-test-v1", () -> { }, () -> { },
                readbackFault);

        ScopedRecoveryVerification retained = unavailable.verify(context(
                incident(PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE),
                fence(PersistenceDomain.OWNER_MUTATION)));

        assertEquals(ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE, retained.resolution());
        assertEquals(null, retained.indexPublisher());

        DeterministicPersistenceFaultInjector publicationFault = new DeterministicPersistenceFaultInjector()
                .arm("publication-boundary", PersistenceCheckpoint.BEFORE_RUNTIME_INDEX_PUBLICATION,
                        FaultMode.RUNTIME_PUBLICATION_EXCEPTION, 1);
        PostCommitPublicationRecoveryVerifier publishFails = new PostCommitPublicationRecoveryVerifier(
                PersistenceDomain.OWNER_MUTATION, "owner-test-v2", () -> { }, () -> { },
                publicationFault);
        ScopedRecoveryVerification verified = publishFails.verify(context(
                incident(PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE),
                fence(PersistenceDomain.OWNER_MUTATION)));

        assertNotNull(verified.indexPublisher());
        assertThrows(Exception.class, verified.indexPublisher()::publish);
    }

    private PostCommitPublicationRecoveryVerifier verifier(
            PostCommitPublicationRecoveryVerifier.CanonicalAuthorityProbe probe,
            StorageRecoveryIndexPublisher publisher) {
        return new PostCommitPublicationRecoveryVerifier(
                PersistenceDomain.OWNER_MUTATION, "owner-test-v1", probe, publisher);
    }

    private ScopedRecoveryContext context(PersistenceIncident incident,
                                          PersistenceQuarantineRecord fence) {
        return new ScopedRecoveryContext(incident, List.of(fence),
                ScopedRecoveryTrigger.OPERATOR_REQUEST);
    }

    private PersistenceIncident incident(PersistenceFailureClass failureClass) {
        return new PersistenceIncident(
                "incident-a", "fingerprint", PersistenceIncidentStatus.OPEN,
                PersistenceIncidentSeverity.ERROR, failureClass,
                PersistenceDisposition.SCOPED_QUARANTINE, PersistenceDomain.OWNER_MUTATION,
                PersistenceOperationPhase.PUBLICATION, "publication_failed", "operation-a",
                "boot-a", 1L, 1L, 0L, 1L, 0L, null, null, "{}", null, null);
    }

    private PersistenceQuarantineRecord fence(PersistenceDomain domain) {
        PersistenceScope scope = new PersistenceScope(
                PersistenceScopeType.PROFILE, "profile-a", "scope-hash", "profile_catalog");
        return new PersistenceQuarantineRecord(
                "quarantine-a", "incident-a", scope, domain, "publication_failed",
                PersistenceQuarantineState.ACTIVE, "evidence-hash", 0L,
                1L, 1L, 0L, null);
    }
}

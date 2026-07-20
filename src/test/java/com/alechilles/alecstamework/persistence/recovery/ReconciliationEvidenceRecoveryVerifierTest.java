package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.ownership.reconciliation.ReconciliationEvidenceRecoveryProofRegistry;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReconciliationEvidenceRecoveryVerifierTest {
    @Test
    void stagedProofCannotClearUntilFinalReconciliationFenceSealsIt() {
        ReconciliationEvidenceRecoveryProofRegistry proofs =
                new ReconciliationEvidenceRecoveryProofRegistry();
        proofs.stage("scan-a", Set.of("profile-a"));
        ReconciliationEvidenceRecoveryVerifier verifier = new ReconciliationEvidenceRecoveryVerifier(
                proofs, () -> { }, () -> { }
        );

        ScopedRecoveryVerification staged = verifier.verify(context());
        proofs.seal("scan-a");
        ScopedRecoveryVerification sealed = verifier.verify(context());

        assertEquals(ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE, staged.resolution());
        assertEquals(ScopedRecoveryResolution.RESOLVED_NEW_STATE, sealed.resolution());
        assertEquals("evidence-hash", sealed.evidenceHashes().get("quarantine-a"));
    }

    @Test
    void sealedFreshProofRequiresCanonicalReadbackBeforePublishing() throws Exception {
        ReconciliationEvidenceRecoveryProofRegistry proofs =
                new ReconciliationEvidenceRecoveryProofRegistry();
        proofs.stage("scan-a", Set.of("profile-a"));
        proofs.seal("scan-a");
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        ReconciliationEvidenceRecoveryVerifier verifier = new ReconciliationEvidenceRecoveryVerifier(
                proofs, reads::incrementAndGet, publications::incrementAndGet
        );

        ScopedRecoveryVerification result = verifier.verify(context());

        assertEquals(ScopedRecoveryResolution.RESOLVED_NEW_STATE, result.resolution());
        assertEquals(1, reads.get());
        assertNotNull(result.indexPublisher());
        assertEquals(0, publications.get());
        result.indexPublisher().publish();
        assertEquals(1, publications.get());
    }

    @Test
    void invalidatedProofRetainsTheQuarantine() {
        ReconciliationEvidenceRecoveryProofRegistry proofs =
                new ReconciliationEvidenceRecoveryProofRegistry();
        proofs.stage("scan-a", Set.of("profile-a"));
        proofs.seal("scan-a");
        proofs.invalidate("scan-a");
        ReconciliationEvidenceRecoveryVerifier verifier = new ReconciliationEvidenceRecoveryVerifier(
                proofs, () -> { }, () -> { }
        );

        assertEquals(
                ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE,
                verifier.verify(context()).resolution()
        );
    }

    private static ScopedRecoveryContext context() {
        PersistenceIncident incident = new PersistenceIncident(
                "incident-a", "fingerprint", PersistenceIncidentStatus.OPEN,
                PersistenceIncidentSeverity.ERROR,
                PersistenceFailureClass.SCOPED_IDENTITY_CONTRADICTION,
                PersistenceDisposition.SCOPED_QUARANTINE,
                PersistenceDomain.RECONCILIATION,
                PersistenceOperationPhase.RECOVERY,
                "reconciliation_evidence_conflict_conflicting_owner_evidence",
                null, "boot-a", 1L, 1L, 0L, 1L, 0L,
                null, null, "{}", null, null
        );
        PersistenceScope scope = new PersistenceScope(
                PersistenceScopeType.PROFILE, "profile-a", "scope-hash", "profile_catalog"
        );
        PersistenceQuarantineRecord quarantine = new PersistenceQuarantineRecord(
                "quarantine-a", "incident-a", scope, PersistenceDomain.RECONCILIATION,
                "reconciliation_evidence_conflict_conflicting_owner_evidence",
                PersistenceQuarantineState.ACTIVE, "evidence-hash", 0L,
                1L, 1L, 0L, null
        );
        return new ScopedRecoveryContext(
                incident, List.of(quarantine), ScopedRecoveryTrigger.OPERATOR_REQUEST
        );
    }
}

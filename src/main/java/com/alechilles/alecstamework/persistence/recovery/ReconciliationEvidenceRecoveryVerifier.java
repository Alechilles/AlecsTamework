package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.ownership.reconciliation.ReconciliationEvidenceRecoveryProofRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Clears reconciliation contradiction fences only after a fresh complete scan proves the profile. */
public final class ReconciliationEvidenceRecoveryVerifier implements ScopedPersistenceRecoveryVerifier {
    private static final String REASON_PREFIX = "reconciliation_evidence_conflict_";

    private final ReconciliationEvidenceRecoveryProofRegistry proofs;
    private final PostCommitPublicationRecoveryVerifier.CanonicalAuthorityProbe authorityProbe;
    private final StorageRecoveryIndexPublisher indexPublisher;

    public ReconciliationEvidenceRecoveryVerifier(
            @Nonnull ReconciliationEvidenceRecoveryProofRegistry proofs,
            @Nonnull PostCommitPublicationRecoveryVerifier.CanonicalAuthorityProbe authorityProbe,
            @Nonnull StorageRecoveryIndexPublisher indexPublisher
    ) {
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.authorityProbe = Objects.requireNonNull(authorityProbe, "authorityProbe");
        this.indexPublisher = Objects.requireNonNull(indexPublisher, "indexPublisher");
    }

    @Override
    @Nonnull
    public PersistenceDomain domain() {
        return PersistenceDomain.RECONCILIATION;
    }

    @Override
    @Nonnull
    public String verifierId() {
        return "reconciliation-evidence-v1";
    }

    @Override
    public boolean supports(@Nonnull PersistenceFailureClass failureClass) {
        return failureClass == PersistenceFailureClass.SCOPED_IDENTITY_CONTRADICTION;
    }

    @Override
    @Nonnull
    public ScopedRecoveryVerification verify(@Nonnull ScopedRecoveryContext context) {
        if (!validIncident(context)) {
            return ScopedRecoveryVerification.unresolved(
                    ScopedRecoveryResolution.CONTRADICTORY_EVIDENCE,
                    "reconciliation_evidence_scope_mismatch"
            );
        }
        for (PersistenceQuarantineRecord quarantine : context.quarantines()) {
            if (!proofs.isSealedConflictFree(quarantine.scope().key())) {
                return ScopedRecoveryVerification.unresolved(
                        ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE,
                        "fresh_reconciliation_evidence_unavailable"
                );
            }
        }
        try {
            authorityProbe.verifyReadable();
        } catch (Exception failure) {
            return new ScopedRecoveryVerification(
                    ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE,
                    "canonical_readback_unavailable",
                    Map.of(),
                    null,
                    failure
            );
        }
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        for (PersistenceQuarantineRecord quarantine : context.quarantines()) {
            hashes.put(quarantine.quarantineId(), quarantine.evidenceHash());
        }
        return new ScopedRecoveryVerification(
                ScopedRecoveryResolution.RESOLVED_NEW_STATE,
                "fresh_reconciliation_conflict_free",
                hashes,
                indexPublisher,
                null
        );
    }

    private boolean validIncident(ScopedRecoveryContext context) {
        if (context.incident().domain() != PersistenceDomain.RECONCILIATION
                || context.incident().failureClass()
                != PersistenceFailureClass.SCOPED_IDENTITY_CONTRADICTION
                || !context.incident().reasonCode().startsWith(REASON_PREFIX)
                || context.quarantines().isEmpty()) {
            return false;
        }
        for (PersistenceQuarantineRecord quarantine : context.quarantines()) {
            if (quarantine.domain() != PersistenceDomain.RECONCILIATION
                    || quarantine.scope().type() != PersistenceScopeType.PROFILE
                    || !quarantine.reasonCode().startsWith(REASON_PREFIX)) {
                return false;
            }
        }
        return true;
    }
}

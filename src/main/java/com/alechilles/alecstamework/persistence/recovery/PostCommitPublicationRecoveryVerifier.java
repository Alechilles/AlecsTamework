package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpointHook;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Clears only after-commit publication incidents after a fresh canonical read and index reload.
 * Other failure classes require a domain verifier that can reason about physical evidence.
 */
public final class PostCommitPublicationRecoveryVerifier implements ScopedPersistenceRecoveryVerifier {
    private final PersistenceDomain domain;
    private final String verifierId;
    private final CanonicalAuthorityProbe authorityProbe;
    private final StorageRecoveryIndexPublisher indexPublisher;
    private final PersistenceCheckpointHook checkpoints;

    public PostCommitPublicationRecoveryVerifier(
            @Nonnull PersistenceDomain domain,
            @Nonnull String verifierId,
            @Nonnull CanonicalAuthorityProbe authorityProbe,
            @Nonnull StorageRecoveryIndexPublisher indexPublisher) {
        this(domain, verifierId, authorityProbe, indexPublisher, PersistenceCheckpointHook.NO_OP);
    }

    PostCommitPublicationRecoveryVerifier(
            @Nonnull PersistenceDomain domain,
            @Nonnull String verifierId,
            @Nonnull CanonicalAuthorityProbe authorityProbe,
            @Nonnull StorageRecoveryIndexPublisher indexPublisher,
            @Nonnull PersistenceCheckpointHook checkpoints) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.verifierId = requireText(verifierId, "verifierId");
        this.authorityProbe = Objects.requireNonNull(authorityProbe, "authorityProbe");
        this.indexPublisher = Objects.requireNonNull(indexPublisher, "indexPublisher");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    @Override
    @Nonnull
    public PersistenceDomain domain() {
        return domain;
    }

    @Override
    @Nonnull
    public String verifierId() {
        return verifierId;
    }

    @Override
    public boolean supports(@Nonnull PersistenceFailureClass failureClass) {
        return failureClass == PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE;
    }

    @Override
    @Nonnull
    public ScopedRecoveryVerification verify(@Nonnull ScopedRecoveryContext context) {
        if (context.incident().failureClass()
                != PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE) {
            return unresolved(context.incident().failureClass());
        }
        if (!matchesDomain(context)) {
            return ScopedRecoveryVerification.unresolved(
                    ScopedRecoveryResolution.CONTRADICTORY_EVIDENCE,
                    "quarantine_domain_mismatch");
        }
        try {
            authorityProbe.verifyReadable();
            checkpoints.hit(PersistenceCheckpoint.AFTER_CANONICAL_READBACK, null);
            return new ScopedRecoveryVerification(
                    ScopedRecoveryResolution.RESOLVED_NEW_STATE,
                    "committed_state_readback_verified",
                    evidenceHashes(context),
                    checkpointedPublisher(),
                    null);
        } catch (Exception failure) {
            return new ScopedRecoveryVerification(
                    ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE,
                    "canonical_readback_unavailable",
                    Map.of(),
                    null,
                    failure);
        }
    }

    private StorageRecoveryIndexPublisher checkpointedPublisher() {
        return () -> {
            checkpoints.hit(PersistenceCheckpoint.BEFORE_RUNTIME_INDEX_PUBLICATION, null);
            indexPublisher.publish();
            checkpoints.hit(PersistenceCheckpoint.AFTER_RUNTIME_INDEX_PUBLICATION, null);
        };
    }

    private boolean matchesDomain(ScopedRecoveryContext context) {
        if (context.incident().domain() != domain || context.quarantines().isEmpty()) return false;
        for (PersistenceQuarantineRecord quarantine : context.quarantines()) {
            if (quarantine.domain() != domain
                    || !quarantine.incidentId().equals(context.incident().incidentId())) return false;
        }
        return true;
    }

    private Map<String, String> evidenceHashes(ScopedRecoveryContext context) {
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        for (PersistenceQuarantineRecord quarantine : context.quarantines()) {
            hashes.put(quarantine.quarantineId(), quarantine.evidenceHash());
        }
        return Map.copyOf(hashes);
    }

    private ScopedRecoveryVerification unresolved(PersistenceFailureClass failureClass) {
        ScopedRecoveryResolution resolution = failureClass
                == PersistenceFailureClass.SCOPED_IDENTITY_CONTRADICTION
                ? ScopedRecoveryResolution.CONTRADICTORY_EVIDENCE
                : ScopedRecoveryResolution.STILL_AMBIGUOUS;
        return ScopedRecoveryVerification.unresolved(
                resolution, "domain_evidence_verifier_required");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
        return value.trim();
    }

    /** Fresh read-only proof that the domain's canonical authority can be enumerated. */
    @FunctionalInterface
    public interface CanonicalAuthorityProbe {
        void verifyReadable() throws Exception;
    }
}

package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRecord;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeType;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Clears operation-ambiguity fences only after the exact journal has a terminal durable result. */
public final class ReconciliationOperationRecoveryVerifier
        implements ScopedPersistenceRecoveryVerifier {
    private final OperationResolutionProbe operationProbe;
    private final PostCommitPublicationRecoveryVerifier.CanonicalAuthorityProbe authorityProbe;
    private final StorageRecoveryIndexPublisher indexPublisher;

    public ReconciliationOperationRecoveryVerifier(
            @Nonnull OperationResolutionProbe operationProbe,
            @Nonnull PostCommitPublicationRecoveryVerifier.CanonicalAuthorityProbe authorityProbe,
            @Nonnull StorageRecoveryIndexPublisher indexPublisher
    ) {
        this.operationProbe = Objects.requireNonNull(operationProbe, "operationProbe");
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
        return "reconciliation-operation-v1";
    }

    @Override
    public boolean supports(@Nonnull PersistenceFailureClass failureClass) {
        return failureClass == PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY;
    }

    @Override
    @Nonnull
    public ScopedRecoveryVerification verify(@Nonnull ScopedRecoveryContext context) {
        if (context.incident().domain() != PersistenceDomain.RECONCILIATION
                || context.incident().failureClass()
                != PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY
                || context.incident().operationId() == null
                || context.quarantines().isEmpty()) {
            return unresolved("reconciliation_operation_scope_mismatch");
        }
        CompanionPopulationOperationRecord operation;
        try {
            operation = operationProbe.requireTerminal(context.incident().operationId());
        } catch (Exception failure) {
            return new ScopedRecoveryVerification(
                    ScopedRecoveryResolution.STILL_AMBIGUOUS,
                    "reconciliation_operation_not_terminal",
                    Map.of(), null, failure
            );
        }
        if (!operation.operationId().equals(context.incident().operationId())
                || !operation.state().isTerminal()
                || !validScopes(context, operation)) {
            return unresolved("reconciliation_operation_scope_mismatch");
        }
        try {
            authorityProbe.verifyReadable();
        } catch (Exception failure) {
            return new ScopedRecoveryVerification(
                    ScopedRecoveryResolution.AUTHORITY_UNAVAILABLE,
                    "canonical_readback_unavailable",
                    Map.of(), null, failure
            );
        }
        ScopedRecoveryResolution resolution = operation.state()
                == CompanionPopulationOperationRecord.State.COMMITTED
                ? ScopedRecoveryResolution.RESOLVED_NEW_STATE
                : ScopedRecoveryResolution.RESOLVED_OLD_STATE;
        return new ScopedRecoveryVerification(
                resolution,
                "reconciliation_operation_terminal_"
                        + operation.state().name().toLowerCase(java.util.Locale.ROOT),
                evidenceHashes(context),
                indexPublisher,
                null
        );
    }

    private static boolean validScopes(
            @Nonnull ScopedRecoveryContext context,
            @Nonnull CompanionPopulationOperationRecord operation
    ) {
        boolean operationScope = false;
        boolean profileScope = false;
        for (PersistenceQuarantineRecord quarantine : context.quarantines()) {
            if (quarantine.domain() != PersistenceDomain.RECONCILIATION
                    || !quarantine.incidentId().equals(context.incident().incidentId())) {
                return false;
            }
            if (quarantine.scope().type() == PersistenceScopeType.OPERATION
                    && quarantine.scope().key().equals(operation.operationId())) {
                operationScope = true;
            } else if (quarantine.scope().type() == PersistenceScopeType.PROFILE
                    && quarantine.scope().key().equals(operation.profileId())) {
                profileScope = true;
            } else {
                return false;
            }
        }
        return operationScope && profileScope;
    }

    @Nonnull
    private static Map<String, String> evidenceHashes(@Nonnull ScopedRecoveryContext context) {
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        for (PersistenceQuarantineRecord quarantine : context.quarantines()) {
            hashes.put(quarantine.quarantineId(), quarantine.evidenceHash());
        }
        return Map.copyOf(hashes);
    }

    @Nonnull
    private static ScopedRecoveryVerification unresolved(@Nonnull String reason) {
        return ScopedRecoveryVerification.unresolved(
                ScopedRecoveryResolution.CONTRADICTORY_EVIDENCE, reason
        );
    }

    @FunctionalInterface
    public interface OperationResolutionProbe {
        @Nonnull
        CompanionPopulationOperationRecord requireTerminal(@Nonnull String operationId)
                throws Exception;
    }
}

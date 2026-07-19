package com.alechilles.alecstamework.persistence.incidents;

import javax.annotation.Nonnull;

/** Applies the mandatory transaction, bounded-scope, durable-fence, and authority decision order. */
public final class PersistenceFailureClassifier {

    @Nonnull
    public PersistenceFailureClassification classify(@Nonnull PersistenceFailureContext context) {
        if (context.storageIntegrityFailed()) {
            return global(PersistenceFailureClass.STORAGE_INTEGRITY_LOST,
                    PersistenceDisposition.OPERATOR_RESTORE_REQUIRED, context);
        }
        if (context.storageUnavailable()) {
            return global(PersistenceFailureClass.STORAGE_UNAVAILABLE,
                    PersistenceDisposition.GLOBAL_READ_ONLY, context);
        }
        if (context.transactionOutcome() == PersistenceTransactionOutcome.UNKNOWN) {
            return global(PersistenceFailureClass.UNKNOWN_TRANSACTION_OUTCOME,
                    PersistenceDisposition.GLOBAL_READ_ONLY, context);
        }
        if (context.transientContention()) {
            return scoped(PersistenceFailureClass.TRANSIENT_CONTENTION,
                    PersistenceDisposition.RETRY_SAME_OPERATION, context);
        }
        if (context.coverageUnavailable()) {
            return scoped(PersistenceFailureClass.COVERAGE_UNAVAILABLE,
                    PersistenceDisposition.AUTHORITY_NOT_READY, context);
        }
        if (context.transactionOutcome() == PersistenceTransactionOutcome.COMMITTED) {
            return boundedOrGlobal(PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE, context);
        }
        if (context.identityContradiction()) {
            return boundedOrGlobal(PersistenceFailureClass.SCOPED_IDENTITY_CONTRADICTION, context);
        }
        if (context.liveMutationMayBeVisible()) {
            return boundedOrGlobal(PersistenceFailureClass.SCOPED_APPLY_AMBIGUITY, context);
        }
        if (context.transactionOutcome() == PersistenceTransactionOutcome.ROLLED_BACK) {
            if (context.scopes().isEmpty()) {
                return scoped(PersistenceFailureClass.DEFINITIVE_PRE_APPLY_FAILURE,
                        PersistenceDisposition.CANCEL_OPERATION, context);
            }
            return scoped(PersistenceFailureClass.ROLLED_BACK_DOMAIN_CONFLICT,
                    PersistenceDisposition.DOMAIN_REJECTION, context);
        }
        return scoped(PersistenceFailureClass.DEFINITIVE_PRE_APPLY_FAILURE,
                PersistenceDisposition.CANCEL_OPERATION, context);
    }

    private PersistenceFailureClassification boundedOrGlobal(PersistenceFailureClass type,
                                                              PersistenceFailureContext context) {
        if (context.scopes().isEmpty() || !context.durableFenceAvailable() || !context.canonicalStateReadable()) {
            return global(type, PersistenceDisposition.GLOBAL_READ_ONLY, context);
        }
        return scoped(type, PersistenceDisposition.SCOPED_QUARANTINE, context);
    }

    private PersistenceFailureClassification scoped(PersistenceFailureClass type,
                                                     PersistenceDisposition disposition,
                                                     PersistenceFailureContext context) {
        return new PersistenceFailureClassification(type, disposition, context.scopes(), false);
    }

    private PersistenceFailureClassification global(PersistenceFailureClass type,
                                                     PersistenceDisposition disposition,
                                                     PersistenceFailureContext context) {
        return new PersistenceFailureClassification(type, disposition, context.scopes(), true);
    }
}

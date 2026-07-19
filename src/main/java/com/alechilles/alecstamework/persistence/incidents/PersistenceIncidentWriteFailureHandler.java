package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.operation.PersistenceOperationMetadata;
import com.alechilles.alecstamework.persistence.operation.PersistenceWriteFailureHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;

/** Translates writer outcomes into central failure-classifier evidence without owning queue policy. */
public final class PersistenceIncidentWriteFailureHandler implements PersistenceWriteFailureHandler {
    private final PersistenceIncidentReporter reporter;

    public PersistenceIncidentWriteFailureHandler(@Nonnull PersistenceIncidentReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public void rolledBack(@Nonnull PersistenceOperationMetadata metadata,
                           @Nonnull Throwable failure) {
        if (metadata.legacySubmission()) return;
        reporter.report(context(
                "write_rolled_back_" + token(metadata.taskName()), metadata,
                PersistenceTransactionOutcome.ROLLED_BACK, false, failure));
    }

    @Override
    public void commitOutcomeUnknown(@Nonnull List<PersistenceOperationMetadata> metadata,
                                     @Nonnull Throwable failure) {
        PersistenceOperationMetadata representative = metadata.isEmpty()
                ? PersistenceOperationMetadata.legacy("unknown_write_batch")
                : metadata.getFirst();
        List<PersistenceScope> scopes = distinctScopes(metadata);
        reporter.report(new PersistenceFailureContext(
                "write_commit_outcome_unknown", representative.domain(),
                PersistenceOperationPhase.COMMIT, PersistenceTransactionOutcome.UNKNOWN,
                scopes, false, false, false, false, false, false, false,
                metadata.stream().anyMatch(PersistenceOperationMetadata::liveMutationMayBeVisible),
                representative.operationId(), failure));
    }

    @Override
    public void publicationFailed(@Nonnull PersistenceOperationMetadata metadata,
                                  @Nonnull Throwable failure) {
        if (metadata.legacySubmission()) return;
        reporter.report(context(
                "write_publication_failed_" + token(metadata.taskName()), metadata,
                PersistenceTransactionOutcome.COMMITTED, true, failure));
    }

    private PersistenceFailureContext context(String reason,
                                              PersistenceOperationMetadata metadata,
                                              PersistenceTransactionOutcome outcome,
                                              boolean liveMayBeVisible,
                                              Throwable failure) {
        return new PersistenceFailureContext(
                reason, metadata.domain(), metadata.phase(), outcome, metadata.scopes(),
                metadata.durableFenceAvailable(), metadata.canonicalStateReadable(),
                false, false, false, false, false,
                liveMayBeVisible || metadata.liveMutationMayBeVisible(),
                metadata.operationId(), failure);
    }

    private List<PersistenceScope> distinctScopes(List<PersistenceOperationMetadata> metadata) {
        LinkedHashMap<PersistenceScope.ScopeKey, PersistenceScope> scopes = new LinkedHashMap<>();
        for (PersistenceOperationMetadata operation : metadata) {
            for (PersistenceScope scope : operation.scopes()) scopes.putIfAbsent(scope.lookupKey(), scope);
        }
        return List.copyOf(new ArrayList<>(scopes.values()));
    }

    private String token(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}

package com.alechilles.alecstamework.persistence.incidents;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Value-only evidence submitted by a domain boundary to the central failure classifier. */
public record PersistenceFailureContext(@Nonnull String reasonCode,
                                        @Nonnull PersistenceDomain domain,
                                        @Nonnull PersistenceOperationPhase phase,
                                        @Nonnull PersistenceTransactionOutcome transactionOutcome,
                                        @Nonnull List<PersistenceScope> scopes,
                                        boolean durableFenceAvailable,
                                        boolean canonicalStateReadable,
                                        boolean storageIntegrityFailed,
                                        boolean storageUnavailable,
                                        boolean transientContention,
                                        boolean identityContradiction,
                                        boolean coverageUnavailable,
                                        boolean liveMutationMayBeVisible,
                                        @Nullable String operationId,
                                        @Nullable Throwable failure) {
    public PersistenceFailureContext {
        reasonCode = requireText(reasonCode);
        if (domain == null || phase == null || transactionOutcome == null) {
            throw new IllegalArgumentException("domain, phase, and transactionOutcome are required");
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        operationId = operationId == null || operationId.isBlank() ? null : operationId.trim();
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("reasonCode");
        return value.trim();
    }
}

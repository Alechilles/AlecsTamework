package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Bounded end-to-end recovery outcome for all replacement operations. */
public record SqlitePublicRecoveryResult(
        @Nonnull Status status,
        int passCount,
        int completedCount,
        int deferredCount,
        @Nonnull List<OperationScope> quarantinedScopes,
        @Nullable Throwable failure
) {
    public SqlitePublicRecoveryResult {
        if (status == null || passCount < 0 || completedCount < 0
                || deferredCount < 0
                || quarantinedScopes == null
                || quarantinedScopes.stream().anyMatch(java.util.Objects::isNull)
                || (status == Status.COMPLETE) != (failure == null)) {
            throw new IllegalArgumentException(
                    "Complete public recovery result is required"
            );
        }
        quarantinedScopes = List.copyOf(
                new java.util.TreeSet<>(quarantinedScopes)
        );
    }

    public enum Status {
        COMPLETE,
        SCAN_FAILED,
        DISPATCH_FAILED,
        UNRESOLVED,
        PASS_LIMIT_REACHED
    }
}

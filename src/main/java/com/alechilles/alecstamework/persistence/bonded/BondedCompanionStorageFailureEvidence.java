package com.alechilles.alecstamework.persistence.bonded;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Privacy-safe evidence captured when bonded SQLite storage fails at runtime. */
public record BondedCompanionStorageFailureEvidence(
        @Nonnull String operation,
        @Nonnull String failureClass,
        @Nonnull String failureReason,
        @Nonnull String baselineFileState,
        @Nonnull String baselineSizeBucket,
        @Nonnull String failureFileState,
        @Nonnull String failureSizeBucket,
        @Nonnull String identityComparison,
        @Nonnull String sizeComparison,
        @Nonnull String modifiedComparison,
        boolean walPresent,
        boolean shmPresent,
        @Nonnull String schemaStatus,
        @Nonnull String schemaDiagnostic,
        int sqlErrorCode,
        @Nonnull String sqlState,
        @Nonnull Throwable failure
) {
    public BondedCompanionStorageFailureEvidence {
        operation = requireText(operation, "operation");
        failureClass = requireText(failureClass, "failureClass");
        failureReason = requireText(failureReason, "failureReason");
        baselineFileState = requireText(baselineFileState, "baselineFileState");
        baselineSizeBucket = requireText(baselineSizeBucket, "baselineSizeBucket");
        failureFileState = requireText(failureFileState, "failureFileState");
        failureSizeBucket = requireText(failureSizeBucket, "failureSizeBucket");
        identityComparison = requireText(identityComparison, "identityComparison");
        sizeComparison = requireText(sizeComparison, "sizeComparison");
        modifiedComparison = requireText(modifiedComparison, "modifiedComparison");
        schemaStatus = requireText(schemaStatus, "schemaStatus");
        schemaDiagnostic = requireText(schemaDiagnostic, "schemaDiagnostic");
        sqlState = requireText(sqlState, "sqlState");
        failure = Objects.requireNonNull(failure, "failure");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}

package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit result of one bounded recovery enumeration and lease pass. */
public record OperationRecoveryScanResult(@Nonnull Status status,
                                          @Nonnull List<OperationRecoveryClaim> claims,
                                          @Nonnull List<OperationRecoveryIssue> issues,
                                          int skippedQuarantined,
                                          @Nullable StorageFailure storageFailure) {
    public OperationRecoveryScanResult {
        if (status == null || claims == null || issues == null || skippedQuarantined < 0) {
            throw new IllegalArgumentException("Complete recovery scan result is required");
        }
        claims = List.copyOf(claims);
        issues = List.copyOf(issues);
        if ((status == Status.READ_FAILED) != (storageFailure != null)) {
            throw new IllegalArgumentException("Only failed recovery scans carry storage failure");
        }
    }

    public enum Status {
        COMPLETE,
        READ_FAILED
    }
}

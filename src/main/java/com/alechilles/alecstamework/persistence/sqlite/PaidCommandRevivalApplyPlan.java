package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Frozen projection identity and optional lease policy for one in-flight paid revival. */
public record PaidCommandRevivalApplyPlan(
        @Nonnull UUID operationId,
        @Nonnull UUID projectionNpcUuid,
        @Nullable PaidCommandRevivalApplyCommit.TimedLease timedLease
) {
    public PaidCommandRevivalApplyPlan {
        operationId = Objects.requireNonNull(operationId, "operationId");
        projectionNpcUuid = Objects.requireNonNull(projectionNpcUuid, "projectionNpcUuid");
    }
}

package com.alechilles.alecstamework.companion.lifecycle;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact compare-and-transition request for the one canonical lifecycle revision path.
 *
 * @param expectedRevision revision that must currently be durable
 * @param expectedOperationId active operation fence that must currently be durable
 * @param next complete next lifecycle row
 */
public record LifecycleTransition(@Nonnull LifecycleRevision expectedRevision,
                                  @Nullable OperationId expectedOperationId,
                                  @Nonnull CompanionLifecycle next) {
    public LifecycleTransition {
        if (expectedRevision == null || next == null) {
            throw new IllegalArgumentException("Expected revision and next lifecycle are required");
        }
        if (!next.revision().equals(expectedRevision.next())) {
            throw new IllegalArgumentException("Lifecycle transition must advance exactly one revision");
        }
    }
}

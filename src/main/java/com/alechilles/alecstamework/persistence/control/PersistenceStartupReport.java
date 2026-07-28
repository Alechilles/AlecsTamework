package com.alechilles.alecstamework.persistence.control;

import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable diagnostic snapshot of the replacement engine startup graph. */
public record PersistenceStartupReport(
        @Nonnull Set<PersistenceStartupNode> completedNodes,
        @Nullable PersistenceStartupNode runningNode,
        @Nullable PersistenceStartupNode deferredNode,
        @Nullable PersistenceStartupNode failedNode,
        @Nullable String detail,
        @Nonnull PersistenceReadinessLevel readiness
) {
    public PersistenceStartupReport {
        completedNodes = Set.copyOf(completedNodes);
        if (readiness == null) {
            throw new IllegalArgumentException("Startup readiness is required");
        }
    }

    /** Whether all startup nodes completed and mutation admission was published. */
    public boolean complete() {
        return readiness == PersistenceReadinessLevel.MUTATION_READY;
    }
}

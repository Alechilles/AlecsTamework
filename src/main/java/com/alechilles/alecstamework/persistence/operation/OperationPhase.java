package com.alechilles.alecstamework.persistence.operation;

import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Shared durable phase graph for every replacement persistence operation.
 *
 * <p>Feature-specific progress belongs in typed detail, never in additional shared phases.</p>
 */
public enum OperationPhase {
    PREPARED,
    LIVE_APPLYING,
    DURABLE,
    PUBLISHED,
    COMPENSATING,
    COMPENSATED,
    RETRYABLE,
    FAILED,
    UNKNOWN;

    /** Returns whether the requested transition is an edge in the shared protocol. */
    public boolean canTransitionTo(@Nonnull OperationPhase next) {
        if (next == null) {
            return false;
        }
        return allowedTransitions().contains(next);
    }

    /** Returns the requested phase after validating the shared protocol edge. */
    @Nonnull
    public OperationPhase requireTransitionTo(@Nonnull OperationPhase next) {
        if (!canTransitionTo(next)) {
            throw new IllegalArgumentException("Invalid operation phase transition: " + this + " -> " + next);
        }
        return next;
    }

    /** Returns whether no further durable phase transition is permitted. */
    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    private Set<OperationPhase> allowedTransitions() {
        return switch (this) {
            case PREPARED -> EnumSet.of(LIVE_APPLYING, DURABLE, FAILED);
            case LIVE_APPLYING -> EnumSet.of(DURABLE, RETRYABLE, COMPENSATING, UNKNOWN);
            case DURABLE -> EnumSet.of(PUBLISHED, RETRYABLE);
            case COMPENSATING -> EnumSet.of(COMPENSATED, RETRYABLE, UNKNOWN);
            case RETRYABLE -> EnumSet.of(LIVE_APPLYING, DURABLE, COMPENSATING, FAILED);
            case UNKNOWN -> EnumSet.of(DURABLE, COMPENSATING, FAILED);
            case PUBLISHED, COMPENSATED, FAILED -> EnumSet.noneOf(OperationPhase.class);
        };
    }
}

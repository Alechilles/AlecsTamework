package com.alechilles.alecstamework.persistence.projection;

/**
 * Explicit origin of one checkpointed outbox delivery.
 *
 * <p>The context is supplied by the caller that owns publication. Consumers
 * must never infer recovery from operation phase, event age, or projection
 * state.</p>
 */
public enum ProjectionPublicationContext {
    LIVE_COMMIT,
    RECOVERY_CONVERGENCE
}

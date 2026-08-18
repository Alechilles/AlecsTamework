package com.alechilles.alecstamework.runtime.activation;

/**
 * Result of comparing a frozen startup topology with a reload candidate.
 *
 * <p>The values intentionally expose no apply operation. A topology change is
 * restart-bound because removing live ECS dependencies is not safe.</p>
 */
public enum TameworkTopologyComparison {
    UNCHANGED,
    RESTART_REQUIRED
}

package com.alechilles.alecstamework.runtime.activation;

/**
 * Result of comparing a frozen startup topology with a reload candidate.
 *
 * <p>The values intentionally expose no apply operation. A topology change is
 * restart-bound because removing live ECS dependencies is not safe.</p>
 */
public enum TameworkTopologyComparison {
    UNCHANGED,
    RESTART_REQUIRED;

    public boolean isUnchanged() {
        return this == UNCHANGED;
    }

    public boolean isRestartRequired() {
        return this == RESTART_REQUIRED;
    }

    /** Returns this value for callers that prefer a named status accessor. */
    public TameworkTopologyComparison status() {
        return this;
    }

    /** Alias for {@link #status()}. */
    public TameworkTopologyComparison result() {
        return this;
    }
}

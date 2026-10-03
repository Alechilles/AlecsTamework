package com.alechilles.alecstamework.runtime;

import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Immutable result of one startup probe for durable companion data: whether there is any, and
 * the diagnostic code reported as the activation reason.
 */
public final class TameworkPersistenceActivationEvidence {
    private final boolean durableWork;
    private final String diagnosticCode;

    private TameworkPersistenceActivationEvidence(boolean durableWork, String diagnosticCode) {
        this.durableWork = durableWork;
        this.diagnosticCode = diagnosticCode;
    }

    /** Creates evidence for no durable state. */
    @Nonnull
    public static TameworkPersistenceActivationEvidence dormant() {
        return new TameworkPersistenceActivationEvidence(false, "persistence-absent");
    }

    /** Creates evidence for durable state to recover; {@code evidence} must name at least one source. */
    @Nonnull
    public static TameworkPersistenceActivationEvidence active(@Nonnull Set<String> evidence) {
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("Active persistence evidence cannot be empty");
        }
        return new TameworkPersistenceActivationEvidence(true, "durable-state-present");
    }

    /** Returns a stable bounded diagnostic code. */
    @Nonnull
    public String diagnosticCode() {
        return diagnosticCode;
    }

    /** Returns whether durable state exists, so the persistence runtime is needed. */
    public boolean hasDurableWork() {
        return durableWork;
    }
}

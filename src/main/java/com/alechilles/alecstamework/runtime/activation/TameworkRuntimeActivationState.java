package com.alechilles.alecstamework.runtime.activation;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable published view of one runtime activation plan and its passive
 * diagnostics sink.
 *
 * <p>Callers can publish a complete instance through an
 * {@link AtomicReference} with {@link #publish(AtomicReference,
 * TameworkRuntimeActivationPlan)}. The reference never exposes a partially
 * built plan.</p>
 */
public final class TameworkRuntimeActivationState {
    private final TameworkRuntimeActivationPlan plan;
    private final TameworkRuntimeDiagnostics diagnostics;

    /** Creates one immutable activation state for the supplied plan. */
    public TameworkRuntimeActivationState(TameworkRuntimeActivationPlan plan) {
        this.plan = Objects.requireNonNull(plan, "Activation plan is required");
        this.diagnostics = new TameworkRuntimeDiagnostics(plan);
    }

    /** Creates one immutable activation state for the supplied plan. */
    public static TameworkRuntimeActivationState of(TameworkRuntimeActivationPlan plan) {
        return new TameworkRuntimeActivationState(plan);
    }

    /** Publishes a complete state atomically and returns the published value. */
    public static TameworkRuntimeActivationState publish(
            AtomicReference<TameworkRuntimeActivationState> destination,
            TameworkRuntimeActivationPlan plan
    ) {
        Objects.requireNonNull(destination, "Activation state destination is required");
        TameworkRuntimeActivationState state = new TameworkRuntimeActivationState(plan);
        destination.set(state);
        return state;
    }

    /** Returns the immutable activation plan. */
    public TameworkRuntimeActivationPlan plan() {
        return plan;
    }

    /** Returns whether one module is active. */
    public boolean isActive(TameworkRuntimeModule module) {
        return plan.isActive(module);
    }

    /** Returns whether one module is dormant. */
    public boolean isDormant(TameworkRuntimeModule module) {
        return plan.isDormant(module);
    }

    /** Returns whether one module is unavailable. */
    public boolean isUnavailable(TameworkRuntimeModule module) {
        return plan.isUnavailable(module);
    }

    /** Returns the frozen state of one module. */
    public TameworkRuntimeActivationPlan.ModuleState state(TameworkRuntimeModule module) {
        return plan.state(module);
    }

    /** Returns immutable activation reasons for one module. */
    public Set<TameworkActivationReason> reasonsFor(TameworkRuntimeModule module) {
        return plan.reasonsFor(module);
    }

    /** Returns the frozen topology fingerprint. */
    public String topologyFingerprint() {
        return plan.topologyFingerprint();
    }

    /** Returns the passive diagnostics counter sink and snapshot view. */
    public TameworkRuntimeDiagnostics diagnostics() {
        return diagnostics;
    }
}

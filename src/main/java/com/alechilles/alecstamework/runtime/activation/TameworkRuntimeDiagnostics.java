package com.alechilles.alecstamework.runtime.activation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Passive in-memory diagnostics for one frozen runtime activation plan.
 *
 * <p>Counter updates are explicit participant calls. This class starts no
 * monitor, scheduler, or recurring task. Every read returns immutable plan
 * data and value snapshots of the counters.</p>
 */
public final class TameworkRuntimeDiagnostics {
    private final TameworkRuntimeActivationPlan plan;
    private final Map<TameworkRuntimeModule, Counters> counters;

    /** Creates passive diagnostics for one frozen activation plan. */
    public TameworkRuntimeDiagnostics(TameworkRuntimeActivationPlan plan) {
        this.plan = Objects.requireNonNull(plan, "Activation plan is required");
        Map<TameworkRuntimeModule, Counters> values = new LinkedHashMap<>();
        for (TameworkRuntimeModule module : plan.modules()) {
            values.put(module, new Counters());
        }
        this.counters = Collections.unmodifiableMap(values);
    }

    /** Records one callback for an active module. */
    public void recordCallback(TameworkRuntimeModule module) {
        increment(module, CounterKind.CALLBACKS);
    }

    /** Records one started worker for an active module. */
    public void recordWorkerStart(TameworkRuntimeModule module) {
        increment(module, CounterKind.WORKER_STARTS);
    }

    /** Records one active subscription for an active module. */
    public void recordSubscription(TameworkRuntimeModule module) {
        increment(module, CounterKind.SUBSCRIPTIONS);
    }

    /** Records one database open for an active module. */
    public void recordDatabaseOpen(TameworkRuntimeModule module) {
        increment(module, CounterKind.DATABASE_OPENS);
    }

    /** Returns an immutable value snapshot for one module's counters. */
    public CounterSnapshot countersFor(TameworkRuntimeModule module) {
        return counters(module).snapshot();
    }

    /** Returns state, reasons, and counters for one known module. */
    public ModuleSnapshot module(TameworkRuntimeModule module) {
        TameworkRuntimeActivationPlan.ModuleState state = plan.state(module);
        return new ModuleSnapshot(state, plan.reasonsFor(module), countersFor(module));
    }

    /** Returns immutable diagnostics for every module in stable plan order. */
    public Map<TameworkRuntimeModule, ModuleSnapshot> modules() {
        Map<TameworkRuntimeModule, ModuleSnapshot> snapshots = new LinkedHashMap<>();
        for (TameworkRuntimeModule module : plan.modules()) {
            snapshots.put(module, module(module));
        }
        return Collections.unmodifiableMap(snapshots);
    }

    /** Returns the frozen plan fingerprint shown in diagnostics. */
    public String topologyFingerprint() {
        return plan.topologyFingerprint();
    }

    /** Returns one startup summary suitable for a single INFO log entry. */
    public String startupSummary() {
        return "Runtime activation plan fingerprint=" + topologyFingerprint()
                + ", active=" + ids(plan.activeModules())
                + ", dormant=" + ids(plan.dormantModules())
                + ", unavailable=" + ids(plan.unavailableModules());
    }

    private Counters counters(TameworkRuntimeModule module) {
        plan.state(module);
        Counters value = counters.get(module);
        if (value == null) {
            throw new IllegalArgumentException("Unknown runtime module ID: " + module.id());
        }
        return value;
    }

    private void increment(TameworkRuntimeModule module, CounterKind kind) {
        if (plan.isActive(module)) {
            counters(module).increment(kind);
        }
    }

    private static String ids(Set<TameworkRuntimeModule> modules) {
        return modules.stream().map(TameworkRuntimeModule::id).toList().toString();
    }

    private enum CounterKind {
        CALLBACKS,
        WORKER_STARTS,
        SUBSCRIPTIONS,
        DATABASE_OPENS
    }

    private static final class Counters {
        private final AtomicLong callbacks = new AtomicLong();
        private final AtomicLong workerStarts = new AtomicLong();
        private final AtomicLong subscriptions = new AtomicLong();
        private final AtomicLong databaseOpens = new AtomicLong();

        private void increment(CounterKind kind) {
            switch (kind) {
                case CALLBACKS -> callbacks.incrementAndGet();
                case WORKER_STARTS -> workerStarts.incrementAndGet();
                case SUBSCRIPTIONS -> subscriptions.incrementAndGet();
                case DATABASE_OPENS -> databaseOpens.incrementAndGet();
            }
        }

        private CounterSnapshot snapshot() {
            return new CounterSnapshot(
                    callbacks.get(),
                    workerStarts.get(),
                    subscriptions.get(),
                    databaseOpens.get()
            );
        }
    }

    /** Immutable counter values at one instant. */
    public record CounterSnapshot(
            long callbacks,
            long workerStarts,
            long subscriptions,
            long databaseOpens
    ) {
        public CounterSnapshot {
            if (callbacks < 0L || workerStarts < 0L
                    || subscriptions < 0L || databaseOpens < 0L) {
                throw new IllegalArgumentException("Diagnostic counters cannot be negative");
            }
        }

        /** Returns an all-zero counter snapshot. */
        public static CounterSnapshot zero() {
            return new CounterSnapshot(0L, 0L, 0L, 0L);
        }
    }

    /** Immutable state, reasons, and counter snapshot for one module. */
    public record ModuleSnapshot(
            TameworkRuntimeActivationPlan.ModuleState state,
            Set<TameworkActivationReason> reasons,
            CounterSnapshot counters
    ) {
        public ModuleSnapshot {
            state = Objects.requireNonNull(state, "Module state is required");
            reasons = Collections.unmodifiableSet(new LinkedHashSet<>(
                    Objects.requireNonNull(reasons, "Module reasons are required")
            ));
            counters = Objects.requireNonNull(counters, "Module counters are required");
        }
    }
}

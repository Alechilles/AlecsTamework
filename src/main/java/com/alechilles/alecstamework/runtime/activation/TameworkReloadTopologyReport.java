package com.alechilles.alecstamework.runtime.activation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure comparison result for a frozen startup plan and a reload candidate.
 *
 * <p>The report has no register, unregister, or apply operation. A changed
 * topology remains restart-bound.</p>
 */
public final class TameworkReloadTopologyReport {
    private final TameworkTopologyComparison comparison;
    private final Set<TameworkRuntimeModule> changedModules;
    private final Set<String> changedModuleIds;
    private final String startupFingerprint;
    private final String candidateFingerprint;

    private TameworkReloadTopologyReport(
            TameworkRuntimeActivationPlan startup,
            TameworkRuntimeActivationPlan candidate
    ) {
        Objects.requireNonNull(startup, "Startup activation plan is required");
        Objects.requireNonNull(candidate, "Candidate activation plan is required");
        this.startupFingerprint = startup.topologyFingerprint();
        this.candidateFingerprint = candidate.topologyFingerprint();
        this.comparison = startupFingerprint.equals(candidateFingerprint)
                ? TameworkTopologyComparison.UNCHANGED
                : TameworkTopologyComparison.RESTART_REQUIRED;

        TreeSet<TameworkRuntimeModule> allModules = new TreeSet<>();
        allModules.addAll(startup.modules());
        allModules.addAll(candidate.modules());
        LinkedHashSet<TameworkRuntimeModule> changed = new LinkedHashSet<>();
        for (TameworkRuntimeModule module : allModules) {
            if (!Objects.equals(startup.states().get(module), candidate.states().get(module))
                    || !Objects.equals(
                            startup.dependencies().get(module), candidate.dependencies().get(module)
                    )) {
                changed.add(module);
            }
        }
        this.changedModules = Collections.unmodifiableSet(changed);

        LinkedHashSet<String> changedIds = new LinkedHashSet<>();
        for (TameworkRuntimeModule module : changed) {
            changedIds.add(module.id());
        }
        this.changedModuleIds = Collections.unmodifiableSet(changedIds);
    }

    /** Compares two plans without applying either plan. */
    public static TameworkReloadTopologyReport compare(
            TameworkRuntimeActivationPlan startup,
            TameworkRuntimeActivationPlan candidate
    ) {
        return new TameworkReloadTopologyReport(startup, candidate);
    }

    /** Returns whether a server restart is required. */
    public boolean restartRequired() {
        return comparison == TameworkTopologyComparison.RESTART_REQUIRED;
    }

    /** Returns the comparison enum from the immutable planner contract. */
    public TameworkTopologyComparison comparison() {
        return comparison;
    }

    /** Returns changed modules in stable ID order. */
    public Set<TameworkRuntimeModule> changedModules() {
        return changedModules;
    }

    /** Returns changed stable module IDs in stable ID order. */
    public Set<String> changedModuleIds() {
        return changedModuleIds;
    }

    /** Returns the startup plan fingerprint. */
    public String startupFingerprint() {
        return startupFingerprint;
    }

    /** Returns the candidate plan fingerprint. */
    public String candidateFingerprint() {
        return candidateFingerprint;
    }

    /** Returns a concise operator-facing comparison summary. */
    public String summary() {
        if (!restartRequired()) {
            return "unchanged";
        }
        if (changedModuleIds.isEmpty()) {
            return "restart required";
        }
        return "restart required: " + String.join(", ", changedModuleIds);
    }

    @Override
    public String toString() {
        return summary();
    }
}

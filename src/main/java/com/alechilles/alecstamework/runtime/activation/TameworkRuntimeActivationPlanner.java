package com.alechilles.alecstamework.runtime.activation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure Java planner for the immutable Tamework runtime activation topology.
 *
 * <p>The planner only reads immutable evidence and descriptors. It does not
 * know how Hytale registers systems, starts workers, or delivers events.</p>
 */
public final class TameworkRuntimeActivationPlanner {
    private final TameworkRuntimeModuleCatalog catalog;

    /** Creates a planner for one validated immutable catalog. */
    public TameworkRuntimeActivationPlanner(TameworkRuntimeModuleCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "Runtime module catalog is required");
    }

    /** Returns the catalog used by this planner. */
    public TameworkRuntimeModuleCatalog catalog() {
        return catalog;
    }

    /** Builds a frozen plan from one direct evidence snapshot. */
    public TameworkRuntimeActivationPlan plan(TameworkActivationEvidence evidence) {
        TameworkActivationEvidence checkedEvidence = Objects.requireNonNull(
                evidence,
                "Activation evidence is required"
        );
        validateEvidenceModules(checkedEvidence);

        Map<TameworkRuntimeModule, TameworkRuntimeActivationPlan.ModuleState> states =
                new LinkedHashMap<>();
        Map<TameworkRuntimeModule, Set<TameworkActivationReason>> reasons =
                new LinkedHashMap<>();
        Map<TameworkRuntimeModule, Set<TameworkRuntimeModule>> dependencies =
                new LinkedHashMap<>();
        for (TameworkRuntimeModule module : catalog.modules()) {
            states.put(module, TameworkRuntimeActivationPlan.ModuleState.DORMANT);
            reasons.put(module, new TreeSet<>());
            dependencies.put(module, new TreeSet<>(catalog.directDependencies(module)));
        }

        Set<TameworkRuntimeModule> closure = new LinkedHashSet<>();
        List<TameworkRuntimeModule> directModules = new ArrayList<>(checkedEvidence.directModules());
        Collections.sort(directModules);
        for (TameworkRuntimeModule module : directModules) {
            reasons.get(module).addAll(checkedEvidence.reasonsFor(module));
            expandDependencies(module, List.of(module), closure, reasons);
        }
        for (TameworkRuntimeModule module : closure) {
            states.put(module, TameworkRuntimeActivationPlan.ModuleState.ACTIVE);
        }

        markMissingCapabilities(closure, states, reasons, checkedEvidence);
        propagateUnavailableDependencies(closure, states, reasons);
        pruneUnreachableActiveModules(closure, directModules, states);
        return new TameworkRuntimeActivationPlan(states, reasons, dependencies);
    }

    /** Compares a startup plan with a reload candidate without applying either plan. */
    public TameworkTopologyComparison compare(
            TameworkRuntimeActivationPlan startup,
            TameworkRuntimeActivationPlan candidate
    ) {
        Objects.requireNonNull(startup, "Startup activation plan is required");
        Objects.requireNonNull(candidate, "Candidate activation plan is required");
        return startup.topologyFingerprint().equals(candidate.topologyFingerprint())
                ? TameworkTopologyComparison.UNCHANGED
                : TameworkTopologyComparison.RESTART_REQUIRED;
    }

    /** Returns module IDs whose planned states differ between two snapshots. */
    public Set<TameworkRuntimeModule> changedModules(
            TameworkRuntimeActivationPlan startup,
            TameworkRuntimeActivationPlan candidate
    ) {
        Objects.requireNonNull(startup, "Startup activation plan is required");
        Objects.requireNonNull(candidate, "Candidate activation plan is required");
        Set<TameworkRuntimeModule> modules = new TreeSet<>();
        modules.addAll(startup.modules());
        modules.addAll(candidate.modules());
        Set<TameworkRuntimeModule> changed = new LinkedHashSet<>();
        for (TameworkRuntimeModule module : modules) {
            TameworkRuntimeActivationPlan.ModuleState startupState = stateOrNull(startup, module);
            TameworkRuntimeActivationPlan.ModuleState candidateState = stateOrNull(candidate, module);
            if (startupState != candidateState) {
                changed.add(module);
            }
        }
        return Collections.unmodifiableSet(changed);
    }

    private void expandDependencies(
            TameworkRuntimeModule module,
            List<TameworkRuntimeModule> path,
            Set<TameworkRuntimeModule> closure,
            Map<TameworkRuntimeModule, Set<TameworkActivationReason>> reasons
    ) {
        // The catalog is acyclic, so walking every path records all reasons
        // even when two direct evidence modules share a dependency.
        closure.add(module);
        for (TameworkRuntimeModule dependency : catalog.directDependencies(module)) {
            List<TameworkRuntimeModule> dependencyPath = new ArrayList<>(path);
            dependencyPath.add(dependency);
            reasons.get(dependency).add(TameworkActivationReason.dependency(dependencyPath));
            expandDependencies(dependency, dependencyPath, closure, reasons);
        }
    }

    private void markMissingCapabilities(
            Set<TameworkRuntimeModule> closure,
            Map<TameworkRuntimeModule, TameworkRuntimeActivationPlan.ModuleState> states,
            Map<TameworkRuntimeModule, Set<TameworkActivationReason>> reasons,
            TameworkActivationEvidence evidence
    ) {
        for (TameworkRuntimeModule module : closure) {
            Set<String> required = new TreeSet<>(
                    catalog.descriptor(module).requiredExternalCapabilities()
            );
            required.addAll(evidence.requiredCapabilitiesFor(module));
            for (String capability : required) {
                if (!evidence.hasCapability(capability)) {
                    states.put(module, TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE);
                    reasons.get(module).add(TameworkActivationReason.missingCapability(capability));
                }
            }
        }
    }

    private void propagateUnavailableDependencies(
            Set<TameworkRuntimeModule> closure,
            Map<TameworkRuntimeModule, TameworkRuntimeActivationPlan.ModuleState> states,
            Map<TameworkRuntimeModule, Set<TameworkActivationReason>> reasons
    ) {
        boolean changed;
        do {
            changed = false;
            for (TameworkRuntimeModule module : closure) {
                if (states.get(module) == TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE) {
                    continue;
                }
                for (TameworkRuntimeModule dependency : catalog.directDependencies(module)) {
                    if (states.get(dependency)
                            == TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE) {
                        states.put(module, TameworkRuntimeActivationPlan.ModuleState.UNAVAILABLE);
                        reasons.get(module).add(
                                TameworkActivationReason.dependency(module, dependency)
                        );
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
    }

    private void pruneUnreachableActiveModules(
            Set<TameworkRuntimeModule> closure,
            List<TameworkRuntimeModule> directModules,
            Map<TameworkRuntimeModule, TameworkRuntimeActivationPlan.ModuleState> states
    ) {
        Set<TameworkRuntimeModule> runnableClosure = new LinkedHashSet<>();
        for (TameworkRuntimeModule module : directModules) {
            if (states.get(module) == TameworkRuntimeActivationPlan.ModuleState.ACTIVE) {
                collectRunnableClosure(module, runnableClosure, states);
            }
        }
        for (TameworkRuntimeModule module : closure) {
            if (states.get(module) == TameworkRuntimeActivationPlan.ModuleState.ACTIVE
                    && !runnableClosure.contains(module)) {
                states.put(module, TameworkRuntimeActivationPlan.ModuleState.DORMANT);
            }
        }
    }

    private void collectRunnableClosure(
            TameworkRuntimeModule module,
            Set<TameworkRuntimeModule> runnableClosure,
            Map<TameworkRuntimeModule, TameworkRuntimeActivationPlan.ModuleState> states
    ) {
        if (!runnableClosure.add(module)) {
            return;
        }
        for (TameworkRuntimeModule dependency : catalog.directDependencies(module)) {
            if (states.get(dependency) == TameworkRuntimeActivationPlan.ModuleState.ACTIVE) {
                collectRunnableClosure(dependency, runnableClosure, states);
            }
        }
    }

    private void validateEvidenceModules(TameworkActivationEvidence evidence) {
        for (TameworkRuntimeModule module : evidence.directModules()) {
            if (!catalog.contains(module)) {
                throw new IllegalArgumentException(
                        "Activation evidence names unknown runtime module: " + module.id()
                );
            }
        }
        for (TameworkRuntimeModule module : evidence.requiredCapabilities().keySet()) {
            if (!catalog.contains(module)) {
                throw new IllegalArgumentException(
                        "Capability evidence names unknown runtime module: " + module.id()
                );
            }
        }
    }

    private static TameworkRuntimeActivationPlan.ModuleState stateOrNull(
            TameworkRuntimeActivationPlan plan,
            TameworkRuntimeModule module
    ) {
        return plan.states().get(module);
    }
}

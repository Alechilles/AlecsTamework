package com.alechilles.alecstamework.runtime.activation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * Immutable result of one runtime activation planning pass.
 *
 * <p>The plan contains state, evidence, dependency edges, and a topology
 * fingerprint. It has no operation that can register, unregister, or apply a
 * runtime participant.</p>
 */
public final class TameworkRuntimeActivationPlan {
    /** Runtime state assigned to every module in the catalog. */
    public enum ModuleState {
        ACTIVE,
        DORMANT,
        UNAVAILABLE
    }

    private final Map<TameworkRuntimeModule, ModuleState> states;
    private final Map<TameworkRuntimeModule, Set<TameworkActivationReason>> reasons;
    private final Map<TameworkRuntimeModule, Set<TameworkRuntimeModule>> dependencies;
    private final Set<TameworkRuntimeModule> activeModules;
    private final Set<TameworkRuntimeModule> dormantModules;
    private final Set<TameworkRuntimeModule> unavailableModules;
    private final String topologyFingerprint;

    TameworkRuntimeActivationPlan(
            Map<TameworkRuntimeModule, ModuleState> states,
            Map<TameworkRuntimeModule, Set<TameworkActivationReason>> reasons,
            Map<TameworkRuntimeModule, Set<TameworkRuntimeModule>> dependencies
    ) {
        this.states = immutableStates(states);
        this.reasons = immutableReasons(reasons, this.states.keySet());
        this.dependencies = immutableDependencies(dependencies, this.states.keySet());

        LinkedHashSet<TameworkRuntimeModule> active = new LinkedHashSet<>();
        LinkedHashSet<TameworkRuntimeModule> dormant = new LinkedHashSet<>();
        LinkedHashSet<TameworkRuntimeModule> unavailable = new LinkedHashSet<>();
        for (Map.Entry<TameworkRuntimeModule, ModuleState> entry : this.states.entrySet()) {
            switch (entry.getValue()) {
                case ACTIVE -> active.add(entry.getKey());
                case DORMANT -> dormant.add(entry.getKey());
                case UNAVAILABLE -> unavailable.add(entry.getKey());
            }
        }
        this.activeModules = Collections.unmodifiableSet(active);
        this.dormantModules = Collections.unmodifiableSet(dormant);
        this.unavailableModules = Collections.unmodifiableSet(unavailable);
        this.topologyFingerprint = fingerprint(this.states, this.dependencies);
    }

    /** Returns state keyed by the stable module identity. */
    public Map<TameworkRuntimeModule, ModuleState> states() {
        return states;
    }

    /** Returns the state for one known module. */
    public ModuleState state(TameworkRuntimeModule module) {
        ModuleState state = states.get(module);
        if (state == null) {
            throw new IllegalArgumentException(
                    "Unknown runtime module ID: " + (module == null ? "null" : module.id())
            );
        }
        return state;
    }

    /** Returns whether a module is active in this immutable plan. */
    public boolean isActive(TameworkRuntimeModule module) {
        return state(module) == ModuleState.ACTIVE;
    }

    /** Returns whether a module is dormant in this immutable plan. */
    public boolean isDormant(TameworkRuntimeModule module) {
        return state(module) == ModuleState.DORMANT;
    }

    /** Returns whether a module cannot run because a required capability is unavailable. */
    public boolean isUnavailable(TameworkRuntimeModule module) {
        return state(module) == ModuleState.UNAVAILABLE;
    }

    /** Returns active modules in stable catalog order. */
    public Set<TameworkRuntimeModule> activeModules() {
        return activeModules;
    }

    /** Returns dormant modules in stable catalog order. */
    public Set<TameworkRuntimeModule> dormantModules() {
        return dormantModules;
    }

    /** Returns unavailable modules in stable catalog order. */
    public Set<TameworkRuntimeModule> unavailableModules() {
        return unavailableModules;
    }

    /** Returns every module represented by this plan. */
    public Set<TameworkRuntimeModule> modules() {
        return states.keySet();
    }

    /** Returns all direct and dependency reasons recorded for one module. */
    public Set<TameworkActivationReason> reasonsFor(TameworkRuntimeModule module) {
        state(module);
        return reasons.getOrDefault(module, Set.of());
    }

    /** Returns immutable reasons keyed by module. */
    public Map<TameworkRuntimeModule, Set<TameworkActivationReason>> reasons() {
        return reasons;
    }

    /** Returns direct dependencies declared by the catalog for one module. */
    public Set<TameworkRuntimeModule> dependenciesFor(TameworkRuntimeModule module) {
        state(module);
        return dependencies.getOrDefault(module, Set.of());
    }

    /** Returns dependency edges keyed by module. */
    public Map<TameworkRuntimeModule, Set<TameworkRuntimeModule>> dependencies() {
        return dependencies;
    }

    /** Returns the deterministic SHA-256 topology fingerprint. */
    public String topologyFingerprint() {
        return topologyFingerprint;
    }

    static String fingerprint(
            Map<TameworkRuntimeModule, ModuleState> states,
            Map<TameworkRuntimeModule, Set<TameworkRuntimeModule>> dependencies
    ) {
        List<TameworkRuntimeModule> modules = new ArrayList<>(states.keySet());
        Collections.sort(modules);
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, Integer.toString(modules.size()));
        for (TameworkRuntimeModule module : modules) {
            appendField(canonical, module.id());
            appendField(canonical, states.get(module).name());
            List<String> dependencyIds = dependencies.getOrDefault(module, Set.of()).stream()
                    .map(TameworkRuntimeModule::id)
                    .sorted()
                    .toList();
            appendField(canonical, Integer.toString(dependencyIds.size()));
            for (String dependencyId : dependencyIds) {
                appendField(canonical, dependencyId);
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JVM must provide SHA-256", impossible);
        }
    }

    private static void appendField(StringBuilder canonical, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        canonical.append(encoded.length).append(':').append(value);
    }

    private static Map<TameworkRuntimeModule, ModuleState> immutableStates(
            Map<TameworkRuntimeModule, ModuleState> source
    ) {
        Objects.requireNonNull(source, "Plan states are required");
        List<TameworkRuntimeModule> modules = new ArrayList<>(source.keySet());
        Collections.sort(modules);
        Map<TameworkRuntimeModule, ModuleState> copy = new LinkedHashMap<>();
        for (TameworkRuntimeModule module : modules) {
            if (module == null || source.get(module) == null) {
                throw new IllegalArgumentException("Plan states cannot contain null values");
            }
            copy.put(module, source.get(module));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<TameworkRuntimeModule, Set<TameworkActivationReason>> immutableReasons(
            Map<TameworkRuntimeModule, Set<TameworkActivationReason>> source,
            Set<TameworkRuntimeModule> modules
    ) {
        Objects.requireNonNull(source, "Plan reasons are required");
        Map<TameworkRuntimeModule, Set<TameworkActivationReason>> copy = new LinkedHashMap<>();
        for (TameworkRuntimeModule module : modules) {
            Set<TameworkActivationReason> values = source.getOrDefault(module, Set.of());
            TreeSet<TameworkActivationReason> sorted = new TreeSet<>();
            for (TameworkActivationReason reason : values) {
                sorted.add(Objects.requireNonNull(reason, "Plan reasons cannot contain null"));
            }
            copy.put(module, Collections.unmodifiableSet(new LinkedHashSet<>(sorted)));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<TameworkRuntimeModule, Set<TameworkRuntimeModule>> immutableDependencies(
            Map<TameworkRuntimeModule, Set<TameworkRuntimeModule>> source,
            Set<TameworkRuntimeModule> modules
    ) {
        Objects.requireNonNull(source, "Plan dependencies are required");
        Map<TameworkRuntimeModule, Set<TameworkRuntimeModule>> copy = new LinkedHashMap<>();
        for (TameworkRuntimeModule module : modules) {
            Set<TameworkRuntimeModule> values = source.getOrDefault(module, Set.of());
            TreeSet<TameworkRuntimeModule> sorted = new TreeSet<>();
            for (TameworkRuntimeModule dependency : values) {
                sorted.add(Objects.requireNonNull(dependency, "Plan dependencies cannot contain null"));
            }
            copy.put(module, Collections.unmodifiableSet(new LinkedHashSet<>(sorted)));
        }
        return Collections.unmodifiableMap(copy);
    }
}

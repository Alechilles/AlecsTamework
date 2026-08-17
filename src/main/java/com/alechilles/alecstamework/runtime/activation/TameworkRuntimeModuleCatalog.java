package com.alechilles.alecstamework.runtime.activation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable catalog of module descriptors.
 *
 * <p>Construction validates duplicate module IDs, unknown dependencies, and
 * dependency cycles before a planner can consume the topology.</p>
 */
public final class TameworkRuntimeModuleCatalog {
    private final Map<TameworkRuntimeModule, TameworkRuntimeModuleDescriptor> descriptors;
    private final Set<TameworkRuntimeModule> modules;

    /** Builds and validates a catalog from the complete descriptor set. */
    public TameworkRuntimeModuleCatalog(
            Iterable<TameworkRuntimeModuleDescriptor> descriptors
    ) {
        if (descriptors == null) {
            throw new IllegalArgumentException("Runtime module descriptors are required");
        }
        Map<TameworkRuntimeModule, TameworkRuntimeModuleDescriptor> byModule =
                new LinkedHashMap<>();
        for (TameworkRuntimeModuleDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                throw new IllegalArgumentException("Runtime module descriptors cannot contain null");
            }
            TameworkRuntimeModule module = descriptor.module();
            if (byModule.putIfAbsent(module, descriptor) != null) {
                throw new IllegalArgumentException(
                        "Duplicate runtime module ID: " + module.id()
                );
            }
        }
        if (byModule.isEmpty()) {
            throw new IllegalArgumentException("At least one runtime module is required");
        }
        validateDependencies(byModule);
        this.descriptors = immutableDescriptorMap(byModule);
        this.modules = Collections.unmodifiableSet(new LinkedHashSet<>(this.descriptors.keySet()));
    }

    /** Creates a catalog from descriptors. */
    public static TameworkRuntimeModuleCatalog of(
            TameworkRuntimeModuleDescriptor... descriptors
    ) {
        Objects.requireNonNull(descriptors, "Runtime module descriptors are required");
        return new TameworkRuntimeModuleCatalog(List.of(descriptors));
    }

    /** Creates the current built-in Tamework module topology. */
    public static TameworkRuntimeModuleCatalog standard() {
        TameworkRuntimeModule core = TameworkRuntimeModule.CORE_OWNERSHIP;
        TameworkRuntimeModule interactions = TameworkRuntimeModule.INTERACTIONS;
        TameworkRuntimeModule persistence = TameworkRuntimeModule.GENERIC_PERSISTENCE;
        TameworkRuntimeModule needs = TameworkRuntimeModule.NEEDS;
        TameworkRuntimeModule happiness = TameworkRuntimeModule.HAPPINESS;
        TameworkRuntimeModule food = TameworkRuntimeModule.FOOD;
        TameworkRuntimeModule movement = TameworkRuntimeModule.COMPANION_MOVEMENT;
        TameworkRuntimeModule mounts = TameworkRuntimeModule.MOUNTS;
        TameworkRuntimeModule leveling = TameworkRuntimeModule.LEVELING;

        return new TameworkRuntimeModuleCatalog(List.of(
                TameworkRuntimeModuleDescriptor.of(core),
                TameworkRuntimeModuleDescriptor.of(interactions, core),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.CAPTURE,
                        core,
                        persistence
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.NAMING_ITEMS,
                        core,
                        interactions
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.SPAWNER_ITEMS,
                        core,
                        interactions
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.COMMAND_ITEMS,
                        core,
                        interactions
                ),
                TameworkRuntimeModuleDescriptor.of(mounts, core),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.AVATAR_FLIGHT,
                        core,
                        mounts
                ),
                TameworkRuntimeModuleDescriptor.of(movement, core),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.ATTACHMENTS,
                        core,
                        movement
                ),
                TameworkRuntimeModuleDescriptor.of(needs, core),
                TameworkRuntimeModuleDescriptor.of(
                        happiness,
                        core,
                        needs
                ),
                TameworkRuntimeModuleDescriptor.of(
                        food,
                        core,
                        needs,
                        happiness
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.BREEDING,
                        core,
                        food,
                        happiness
                ),
                TameworkRuntimeModuleDescriptor.of(leveling, core),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.TRAITS,
                        core,
                        leveling
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.TALENTS,
                        core,
                        leveling
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.COOPS,
                        core,
                        food,
                        persistence
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.SCARECROWS,
                        core
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.DAMAGE_PROJECTILES,
                        core
                ),
                TameworkRuntimeModuleDescriptor.of(persistence, core),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.DORMANT_PERSISTENCE,
                        persistence
                ),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.BONDED_PERSISTENCE,
                        core,
                        persistence
                ),
                TameworkRuntimeModuleDescriptor.of(TameworkRuntimeModule.HSTATS, core),
                TameworkRuntimeModuleDescriptor.of(
                        TameworkRuntimeModule.DEBUG_SELF_TEST,
                        core
                )
        ));
    }

    /** Returns the immutable descriptor map keyed for O(1) module lookup. */
    public Map<TameworkRuntimeModule, TameworkRuntimeModuleDescriptor> descriptors() {
        return descriptors;
    }

    /** Returns all catalog module IDs in stable lexical order. */
    public Set<TameworkRuntimeModule> modules() {
        return modules;
    }

    /** Returns whether the module is present in this catalog. */
    public boolean contains(TameworkRuntimeModule module) {
        return module != null && descriptors.containsKey(module);
    }

    /** Performs an O(1) descriptor lookup and rejects unknown modules. */
    public TameworkRuntimeModuleDescriptor descriptor(TameworkRuntimeModule module) {
        TameworkRuntimeModuleDescriptor descriptor = descriptors.get(module);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "Unknown runtime module ID: " + (module == null ? "null" : module.id())
            );
        }
        return descriptor;
    }

    /** Returns direct dependencies through the immutable descriptor snapshot. */
    public Set<TameworkRuntimeModule> directDependencies(TameworkRuntimeModule module) {
        return descriptor(module).directDependencies();
    }

    private static Map<TameworkRuntimeModule, TameworkRuntimeModuleDescriptor> immutableDescriptorMap(
            Map<TameworkRuntimeModule, TameworkRuntimeModuleDescriptor> source
    ) {
        List<TameworkRuntimeModule> sorted = new ArrayList<>(source.keySet());
        Collections.sort(sorted);
        Map<TameworkRuntimeModule, TameworkRuntimeModuleDescriptor> copy = new LinkedHashMap<>();
        for (TameworkRuntimeModule module : sorted) {
            copy.put(module, source.get(module));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void validateDependencies(
            Map<TameworkRuntimeModule, TameworkRuntimeModuleDescriptor> byModule
    ) {
        for (TameworkRuntimeModuleDescriptor descriptor : byModule.values()) {
            for (TameworkRuntimeModule dependency : descriptor.directDependencies()) {
                if (!byModule.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "Unknown dependency " + dependency.id()
                                    + " for module " + descriptor.module().id()
                    );
                }
            }
        }

        Map<TameworkRuntimeModule, VisitState> visits = new HashMap<>();
        for (TameworkRuntimeModule module : new TreeSet<>(byModule.keySet())) {
            visit(module, byModule, visits, new ArrayList<>());
        }
    }

    private static void visit(
            TameworkRuntimeModule module,
            Map<TameworkRuntimeModule, TameworkRuntimeModuleDescriptor> byModule,
            Map<TameworkRuntimeModule, VisitState> visits,
            List<TameworkRuntimeModule> path
    ) {
        VisitState state = visits.get(module);
        if (state == VisitState.COMPLETE) {
            return;
        }
        if (state == VisitState.VISITING) {
            int cycleStart = path.indexOf(module);
            List<TameworkRuntimeModule> cycle = new ArrayList<>(
                    path.subList(Math.max(cycleStart, 0), path.size())
            );
            cycle.add(module);
            throw new IllegalArgumentException(
                    "Runtime module dependency cycle: "
                            + String.join(" -> ", cycle.stream().map(TameworkRuntimeModule::id).toList())
            );
        }

        visits.put(module, VisitState.VISITING);
        path.add(module);
        for (TameworkRuntimeModule dependency : byModule.get(module).directDependencies()) {
            visit(dependency, byModule, visits, path);
        }
        path.remove(path.size() - 1);
        visits.put(module, VisitState.COMPLETE);
    }

    private enum VisitState {
        VISITING,
        COMPLETE
    }
}

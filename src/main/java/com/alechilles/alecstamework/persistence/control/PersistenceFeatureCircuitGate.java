package com.alechilles.alecstamework.persistence.control;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Registry-derived circuit view used by the one mutation admission gate. */
final class PersistenceFeatureCircuitGate {
    private final PersistenceFeatureRegistry registry;
    private Map<PersistenceFeatureId, PersistenceFeatureCircuit> circuits;

    PersistenceFeatureCircuitGate(PersistenceFeatureRegistry registry) {
        this.registry = registry;
        HashMap<PersistenceFeatureId, PersistenceFeatureCircuit> defaults =
                new HashMap<>();
        for (PersistenceFeatureDescriptor descriptor
                : registry.descriptors()) {
            defaults.put(
                    descriptor.featureId(),
                    PersistenceFeatureCircuit.closed(
                            descriptor.featureId(), 0
                    )
            );
        }
        circuits = Map.copyOf(defaults);
    }

    Optional<String> install(
            Map<PersistenceFeatureId, PersistenceFeatureCircuit> replacement
    ) {
        if (replacement == null
                || replacement.values().stream().anyMatch(
                java.util.Objects::isNull
        )) {
            throw new IllegalArgumentException(
                    "Feature circuit snapshot is required"
            );
        }
        Set<PersistenceFeatureId> registered =
                registry.descriptors().stream()
                        .map(PersistenceFeatureDescriptor::featureId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!replacement.keySet().equals(registered)) {
            throw new IllegalArgumentException(
                    "Feature circuits must match the descriptor registry"
            );
        }
        for (Map.Entry<PersistenceFeatureId, PersistenceFeatureCircuit> entry
                : replacement.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().featureId())) {
                throw new IllegalArgumentException(
                        "Feature circuit key and value disagree"
                );
            }
        }
        circuits = Map.copyOf(replacement);
        return circuits.values().stream()
                .filter(PersistenceFeatureCircuit::blocksMutation)
                .map(circuit -> registry.requireFeature(
                        circuit.featureId()
                ))
                .filter(descriptor -> descriptor.circuitPolicy()
                        == PersistenceCircuitPolicy.GLOBAL_FAIL_CLOSED)
                .map(descriptor -> "feature_circuit_open:"
                        + descriptor.featureId())
                .sorted()
                .findFirst();
    }

    boolean blocks(PersistenceFeatureDescriptor descriptor) {
        return blocks(descriptor, new java.util.HashSet<>());
    }

    Map<PersistenceFeatureId, PersistenceFeatureCircuit> snapshot() {
        return circuits;
    }

    private boolean blocks(
            PersistenceFeatureDescriptor descriptor,
            Set<PersistenceFeatureId> visited
    ) {
        if (!visited.add(descriptor.featureId())) {
            return false;
        }
        PersistenceFeatureCircuit own = circuits.get(
                descriptor.featureId()
        );
        if (own == null || own.blocksMutation()) {
            return true;
        }
        for (PersistenceFeatureId dependency
                : descriptor.startupDependencies()) {
            if (blocks(registry.requireFeature(dependency), visited)) {
                return true;
            }
        }
        return false;
    }
}

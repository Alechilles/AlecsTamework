package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/** Immutable, dependency-checked feature registry and operation-definition authority. */
public final class PersistenceFeatureRegistry {
    private final Map<PersistenceFeatureId, PersistenceFeatureDescriptor> features;
    private final Map<OperationKind, PersistenceFeatureDescriptor> operations;
    private final List<PersistenceFeatureDescriptor> startupOrder;
    private final OperationDefinitionRegistry definitions;

    public PersistenceFeatureRegistry(
            @Nonnull Collection<PersistenceFeatureDescriptor> descriptors
    ) {
        if (descriptors == null || descriptors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Persistence feature descriptors are required"
            );
        }
        java.util.HashMap<PersistenceFeatureId, PersistenceFeatureDescriptor>
                byId = new java.util.HashMap<>();
        java.util.HashMap<OperationKind, PersistenceFeatureDescriptor>
                byOperation = new java.util.HashMap<>();
        java.util.HashSet<String> authorities = new java.util.HashSet<>();
        java.util.ArrayList<OperationDefinition<?>> allDefinitions =
                new java.util.ArrayList<>();
        for (PersistenceFeatureDescriptor descriptor : descriptors) {
            validateDescriptor(descriptor, byId, byOperation, authorities);
            byId.put(descriptor.featureId(), descriptor);
            for (OperationDefinition<?> definition
                    : descriptor.operationDefinitions()) {
                byOperation.put(definition.kind(), descriptor);
                allDefinitions.add(definition);
            }
        }
        validateDependencies(byId);
        features = Map.copyOf(byId);
        operations = Map.copyOf(byOperation);
        startupOrder = topologicalOrder(byId);
        definitions = new OperationDefinitionRegistry(allDefinitions);
    }

    @Nonnull
    public List<PersistenceFeatureDescriptor> descriptors() {
        return startupOrder;
    }

    @Nonnull
    public PersistenceFeatureDescriptor requireFeature(
            @Nonnull PersistenceFeatureId featureId
    ) {
        PersistenceFeatureDescriptor descriptor = features.get(featureId);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "Unknown persistence feature: " + featureId
            );
        }
        return descriptor;
    }

    @Nonnull
    public PersistenceFeatureDescriptor requireOperation(
            @Nonnull OperationKind operationKind
    ) {
        PersistenceFeatureDescriptor descriptor = operations.get(operationKind);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "Unknown feature operation: " + operationKind
            );
        }
        return descriptor;
    }

    @Nonnull
    public OperationDefinitionRegistry operationDefinitions() {
        return definitions;
    }

    private void validateDescriptor(
            PersistenceFeatureDescriptor descriptor,
            Map<PersistenceFeatureId, PersistenceFeatureDescriptor> byId,
            Map<OperationKind, PersistenceFeatureDescriptor> byOperation,
            Set<String> authorities
    ) {
        if (descriptor == null
                || byId.containsKey(descriptor.featureId())) {
            throw new IllegalArgumentException(
                    "Feature descriptors require unique IDs"
            );
        }
        for (String authority : descriptor.ownedAuthorities()) {
            if (!authorities.add(authority)) {
                throw new IllegalArgumentException(
                        "Persistence authority has multiple owners: "
                                + authority
                );
            }
        }
        for (OperationDefinition<?> definition
                : descriptor.operationDefinitions()) {
            if (byOperation.containsKey(definition.kind())) {
                throw new IllegalArgumentException(
                        "Operation kind has multiple feature owners: "
                                + definition.kind()
                );
            }
        }
    }

    private void validateDependencies(
            Map<PersistenceFeatureId, PersistenceFeatureDescriptor> byId
    ) {
        for (PersistenceFeatureDescriptor descriptor : byId.values()) {
            for (PersistenceFeatureId dependency
                    : descriptor.startupDependencies()) {
                if (!byId.containsKey(dependency)
                        || dependency.equals(descriptor.featureId())) {
                    throw new IllegalArgumentException(
                            "Feature dependency is missing or self-referential: "
                                    + descriptor.featureId()
                    );
                }
            }
        }
    }

    private List<PersistenceFeatureDescriptor> topologicalOrder(
            Map<PersistenceFeatureId, PersistenceFeatureDescriptor> byId
    ) {
        java.util.ArrayList<PersistenceFeatureDescriptor> ordered =
                new java.util.ArrayList<>();
        java.util.HashSet<PersistenceFeatureId> complete =
                new java.util.HashSet<>();
        while (ordered.size() < byId.size()) {
            boolean progressed = false;
            for (PersistenceFeatureDescriptor descriptor : byId.values()
                    .stream()
                    .sorted(java.util.Comparator.comparing(
                            PersistenceFeatureDescriptor::featureId
                    )).toList()) {
                if (!complete.contains(descriptor.featureId())
                        && complete.containsAll(
                        descriptor.startupDependencies()
                )) {
                    ordered.add(descriptor);
                    complete.add(descriptor.featureId());
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalArgumentException(
                        "Persistence feature dependency cycle"
                );
            }
        }
        return List.copyOf(ordered);
    }
}

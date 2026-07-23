package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/** Complete compile-time declaration of one persistence-affecting feature. */
public record PersistenceFeatureDescriptor(
        @Nonnull PersistenceFeatureId featureId,
        @Nonnull PersistenceFeatureDomain domain,
        @Nonnull Set<String> ownedAuthorities,
        @Nonnull List<OperationDefinition<?>> operationDefinitions,
        @Nonnull Map<OperationKind, Set<OperationScopeType>> operationScopes,
        @Nonnull Set<PersistenceFeatureId> startupDependencies,
        @Nonnull PersistenceFeatureHookId canonicalLoader,
        @Nonnull Set<ProjectionConsumerId> projectionConsumers,
        @Nonnull PersistenceFeatureHookId recoveryHandler,
        @Nonnull Set<PersistenceStartupNode> readinessEvidence,
        @Nonnull PersistenceCircuitPolicy circuitPolicy,
        @Nonnull Set<OperationScopeType> quarantineGranularity,
        @Nonnull PersistenceFeatureHookId shutdownParticipant,
        @Nonnull String metricsNamespace
) {
    public PersistenceFeatureDescriptor {
        if (featureId == null || domain == null || ownedAuthorities == null
                || operationDefinitions == null || operationScopes == null
                || startupDependencies == null || canonicalLoader == null
                || projectionConsumers == null || recoveryHandler == null
                || readinessEvidence == null || circuitPolicy == null
                || quarantineGranularity == null || shutdownParticipant == null
                || metricsNamespace == null || metricsNamespace.isBlank()) {
            throw new IllegalArgumentException(
                    "Complete persistence feature descriptor is required"
            );
        }
        ownedAuthorities = normalizedAuthorities(ownedAuthorities);
        operationDefinitions = List.copyOf(operationDefinitions);
        operationScopes = copyScopes(operationScopes);
        startupDependencies = Set.copyOf(startupDependencies);
        projectionConsumers = Set.copyOf(projectionConsumers);
        readinessEvidence = Set.copyOf(readinessEvidence);
        quarantineGranularity = Set.copyOf(quarantineGranularity);
        metricsNamespace = metricsNamespace.trim();
        validateOperations(operationDefinitions, operationScopes);
        if (readinessEvidence.isEmpty() || quarantineGranularity.isEmpty()) {
            throw new IllegalArgumentException(
                    "Feature readiness and quarantine declarations are required"
            );
        }
    }

    private static Set<String> normalizedAuthorities(Set<String> values) {
        java.util.HashSet<String> normalized = new java.util.HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Feature authority names are required"
                );
            }
            normalized.add(value.trim());
        }
        return Set.copyOf(normalized);
    }

    private static Map<OperationKind, Set<OperationScopeType>> copyScopes(
            Map<OperationKind, Set<OperationScopeType>> scopes
    ) {
        java.util.HashMap<OperationKind, Set<OperationScopeType>> copied =
                new java.util.HashMap<>();
        scopes.forEach((kind, values) -> {
            if (kind == null || values == null || values.isEmpty()
                    || values.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Complete operation scope policy is required"
                );
            }
            copied.put(kind, Set.copyOf(values));
        });
        return Map.copyOf(copied);
    }

    private static void validateOperations(
            List<OperationDefinition<?>> definitions,
            Map<OperationKind, Set<OperationScopeType>> scopes
    ) {
        java.util.HashSet<OperationKind> kinds = new java.util.HashSet<>();
        for (OperationDefinition<?> definition : definitions) {
            if (definition == null || !kinds.add(definition.kind())) {
                throw new IllegalArgumentException(
                        "Feature operation definitions must be unique"
                );
            }
        }
        if (!kinds.equals(scopes.keySet())) {
            throw new IllegalArgumentException(
                    "Every feature operation requires one scope policy"
            );
        }
    }
}

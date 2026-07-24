package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.OperationScopePolicy;
import com.alechilles.alecstamework.persistence.control.PersistenceCircuitPolicy;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDomain;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureHookId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies the one cross-cutting construction convention to feature descriptors. */
final class PublicPersistenceFeatureDescriptorFactory {
    private PublicPersistenceFeatureDescriptorFactory() {
    }

    static PersistenceFeatureDescriptor create(
            PersistenceFeatureId id,
            PersistenceFeatureDomain domain,
            Set<String> authorities,
            List<OperationDefinition<?>> definitions,
            Map<OperationKind, OperationScopePolicy> scopes,
            Set<PersistenceFeatureId> dependencies,
            Set<ProjectionConsumerId> consumers,
            Set<PersistenceStartupNode> readiness,
            Set<OperationScopeType> quarantine
    ) {
        return new PersistenceFeatureDescriptor(
                id,
                domain,
                authorities,
                definitions,
                scopes,
                dependencies,
                hook(id, "loader"),
                consumers,
                hook(id, "recovery"),
                readiness,
                global(id)
                        ? PersistenceCircuitPolicy.GLOBAL_FAIL_CLOSED
                        : PersistenceCircuitPolicy.BOUNDED_SCOPE,
                quarantine,
                hook(id, "shutdown"),
                "persistence." + id
        );
    }

    static Set<PersistenceStartupNode> worldReadiness() {
        return Set.of(
                PersistenceStartupNode.RECOVER_OPERATIONS,
                PersistenceStartupNode.BUILD_PROJECTIONS,
                PersistenceStartupNode.LOAD_FEATURE_DETAIL,
                PersistenceStartupNode.RECONCILE_WORLD
        );
    }

    static Map<OperationKind, OperationScopePolicy> scopes(
            Object... pairs
    ) {
        if (pairs == null || pairs.length == 0
                || pairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Operation scope pairs are required"
            );
        }
        java.util.HashMap<OperationKind, OperationScopePolicy> result =
                new java.util.HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            OperationDefinition<?> definition =
                    (OperationDefinition<?>) pairs[index];
            OperationScopePolicy policy = policy(pairs[index + 1]);
            if (definition == null || policy == null
                    || result.put(definition.kind(), policy) != null) {
                throw new IllegalArgumentException(
                        "Operation scope pairs must be complete and unique"
                );
            }
        }
        return Map.copyOf(result);
    }

    static OperationScopePolicy policy(
            Set<OperationScopeType> required,
            Set<OperationScopeType> optional
    ) {
        return new OperationScopePolicy(required, optional);
    }

    private static OperationScopePolicy policy(Object value) {
        if (value instanceof OperationScopePolicy policy) {
            return policy;
        }
        if (value instanceof Set<?> values) {
            @SuppressWarnings("unchecked")
            Set<OperationScopeType> scopes =
                    (Set<OperationScopeType>) values;
            return OperationScopePolicy.exact(scopes);
        }
        return null;
    }

    private static boolean global(PersistenceFeatureId id) {
        return id.equals(PublicPersistenceFeatureRegistry.IDENTITY)
                || id.equals(PublicPersistenceFeatureRegistry.LIFECYCLE);
    }

    private static PersistenceFeatureHookId hook(
            PersistenceFeatureId id,
            String kind
    ) {
        return new PersistenceFeatureHookId(id + "." + kind);
    }
}

package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact registry-validated lookup for the shared public projection consumers. */
final class SqliteProjectionConsumerSet {
    private final PersistenceFeatureRegistry registry;
    private final Map<ProjectionConsumerId, ProjectionConsumer> consumers;

    SqliteProjectionConsumerSet(
            PersistenceFeatureRegistry registry,
            List<ProjectionConsumer> consumers
    ) {
        if (registry == null || consumers == null) {
            throw new IllegalArgumentException(
                    "Projection registry and consumers are required"
            );
        }
        for (ProjectionConsumer consumer : consumers) {
            if (consumer == null || consumer.consumerId() == null) {
                throw new IllegalArgumentException(
                        "Projection consumer IDs are required"
                );
            }
            ProjectionSubscription subscription = consumer.subscription();
            if (subscription == null) {
                throw new IllegalArgumentException(
                        "Projection consumer subscriptions are required"
                );
            }
        }
        this.registry = registry;
        this.consumers = consumers.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        ProjectionConsumer::consumerId,
                        java.util.function.Function.identity()
                )
        );
        requireExactRegistryConsumers();
    }

    List<ProjectionConsumer> requiredFor(OperationKind operationKind) {
        if (operationKind == null) {
            throw new IllegalArgumentException("Operation kind is required");
        }
        PersistenceFeatureDescriptor feature =
                registry.requireOperation(operationKind);
        return feature.projectionConsumers().stream()
                .sorted(Comparator.comparing(ProjectionConsumerId::value))
                .map(this::require)
                .toList();
    }

    List<ProjectionConsumer> all() {
        return consumers.entrySet().stream()
                .sorted(Comparator.comparing(
                        entry -> entry.getKey().value()
                ))
                .map(Map.Entry::getValue)
                .toList();
    }

    private ProjectionConsumer require(ProjectionConsumerId consumerId) {
        ProjectionConsumer consumer = consumers.get(consumerId);
        if (consumer == null) {
            throw new IllegalArgumentException(
                    "Missing public projection consumer: " + consumerId
            );
        }
        return consumer;
    }

    private void requireExactRegistryConsumers() {
        Set<ProjectionConsumerId> declared = registry.descriptors().stream()
                .flatMap(feature -> feature.projectionConsumers().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!declared.equals(consumers.keySet())) {
            throw new IllegalArgumentException(
                    "Public projection composition does not match registry"
            );
        }
    }
}

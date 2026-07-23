package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.internal.CompanionProfileObserverProjection;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionIndex;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Registry-checked projection composition shared by public work and recovery. */
final class SqlitePublicProjectionSet {
    private final PersistenceFeatureRegistry registry;
    private final ProjectionCoordinator coordinator;
    private final CoopResidencyProjectionIndex coopIndex;
    private final Map<ProjectionConsumerId, ProjectionConsumer> consumers;

    SqlitePublicProjectionSet(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull LongSupplier clock,
            @Nonnull Consumer<NpcProfileChangedEvent> profileListener
    ) {
        if (registry == null || kernel == null || clock == null
                || profileListener == null) {
            throw new IllegalArgumentException(
                    "Public projection dependencies are required"
            );
        }
        this.registry = registry;
        this.coordinator = new ProjectionCoordinator(
                new SqliteProjectionGateway(
                        kernel.reads(),
                        kernel.units()
                ),
                ProjectionRetryPolicy.DEFAULT,
                clock
        );
        CompanionProfileObserverProjection profileObserver =
                new CompanionProfileObserverProjection(profileListener);
        this.coopIndex = new CoopResidencyProjectionIndex();
        this.consumers = Map.of(
                profileObserver.consumerId(),
                profileObserver,
                coopIndex.consumerId(),
                coopIndex
        );
        requireExactRegistryConsumers();
    }

    @Nonnull
    ProjectionCoordinator coordinator() {
        return coordinator;
    }

    @Nonnull
    CoopResidencyProjectionIndex coopIndex() {
        return coopIndex;
    }

    /** Resolves the exact required set from the operation owner's descriptor. */
    @Nonnull
    List<ProjectionConsumer> requiredFor(@Nonnull OperationKind operationKind) {
        if (operationKind == null) {
            throw new IllegalArgumentException("Operation kind is required");
        }
        PersistenceFeatureDescriptor feature =
                registry.requireOperation(operationKind);
        return feature.projectionConsumers().stream()
                .sorted(java.util.Comparator.comparing(
                        ProjectionConsumerId::value
                ))
                .map(this::requireConsumer)
                .toList();
    }

    @Nonnull
    List<ProjectionConsumer> all() {
        return consumers.entrySet().stream()
                .sorted(java.util.Comparator.comparing(
                        entry -> entry.getKey().value()
                ))
                .map(Map.Entry::getValue)
                .toList();
    }

    private ProjectionConsumer requireConsumer(ProjectionConsumerId consumerId) {
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

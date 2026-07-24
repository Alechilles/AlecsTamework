package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.internal.CompanionProfileObserverProjection;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionIndex;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionIndex;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionCatchUpResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Registry-checked projection composition shared by public work and recovery. */
final class SqlitePublicProjectionSet {
    private final PersistenceFeatureRegistry registry;
    private final ProjectionCoordinator coordinator;
    private final CompanionProfileObserverProjection profileObserver;
    private final CoopResidencyProjectionIndex coopIndex;
    private final ProfileExtensionProjectionIndex extensionIndex;
    private final SqliteProfileExtensionReader extensionReader;
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
        this.profileObserver =
                new CompanionProfileObserverProjection(profileListener);
        this.coopIndex = new CoopResidencyProjectionIndex();
        this.extensionIndex = new ProfileExtensionProjectionIndex();
        this.extensionReader =
                new SqliteProfileExtensionReader(kernel.reads());
        this.consumers = List.<ProjectionConsumer>of(
                profileObserver,
                coopIndex,
                extensionIndex
        ).stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ProjectionConsumer::consumerId,
                java.util.function.Function.identity()
        ));
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
    @Nonnull
    CompanionProfileObserverProjection profileIndex() {
        return profileObserver;
    }
    @Nonnull
    ProfileExtensionProjectionIndex extensionIndex() {
        return extensionIndex;
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

    /** Rebuilds canonical indexes before catching every declared consumer up. */
    @Nonnull
    CompletionStage<SqlitePublicProjectionStartupResult> rebuildAndCatchUp(
            @Nonnull SqliteCompanionProfileReader profileReader,
            @Nonnull SqliteCompanionCoopReader coopReader
    ) {
        if (profileReader == null || coopReader == null) {
            throw new IllegalArgumentException(
                    "Canonical projection readers are required"
            );
        }
        return SqliteProfileProjectionBootstrap.rebuild(
                profileReader, profileObserver
        ).thenCompose(result -> {
            if (result.status()
                    != SqliteProfileProjectionBootstrap.Status.COMPLETE) {
                return completed(
                        result.status() == SqliteProfileProjectionBootstrap
                                .Status.CANONICAL_READ_FAILED
                                ? SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_READ_FAILED
                                : SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_REBUILD_FAILED,
                        List.of(),
                        result.failure()
                );
            }
            return rebuildCoop(coopReader);
        });
    }

    private CompletionStage<SqlitePublicProjectionStartupResult> rebuildCoop(
            SqliteCompanionCoopReader coopReader
    ) {
        return coopReader.findAllOccupancies().thenCompose(read -> {
            if (!(read instanceof
                    PersistenceReadResult.Found<List<CoopOccupancy>> found)) {
                return completed(
                        SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_READ_FAILED,
                        List.of(),
                        readFailure("coop", read)
                );
            }
            try {
                coopIndex.rebuild(found.value());
            } catch (Throwable failure) {
                return completed(
                        SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_REBUILD_FAILED,
                        List.of(),
                        failure
                );
            }
            return rebuildDetails();
        });
    }

    private CompletionStage<SqlitePublicProjectionStartupResult>
    rebuildDetails() {
        return SqliteDetailProjectionBootstrap.rebuild(
                extensionReader,
                extensionIndex
        ).thenCompose(result -> {
            if (!result.complete()) {
                return completed(
                        result.status()
                                == SqliteDetailProjectionBootstrap.Status
                                .CANONICAL_READ_FAILED
                                ? SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_READ_FAILED
                                : SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_REBUILD_FAILED,
                        List.of(),
                        result.failure()
                );
            }
            return catchUp(all(), 0, new ArrayList<>());
        });
    }

    private CompletionStage<SqlitePublicProjectionStartupResult> catchUp(
            List<ProjectionConsumer> ordered,
            int index,
            ArrayList<ProjectionCatchUpResult> results
    ) {
        if (index >= ordered.size()) {
            return completed(
                    SqlitePublicProjectionStartupResult.Status.COMPLETE,
                    results,
                    null
            );
        }
        return coordinator.startupCatchUp(
                ordered.get(index),
                256
        ).thenCompose(result -> {
            results.add(result);
            if (result.status() != ProjectionCatchUpResult.Status.CAUGHT_UP) {
                return completed(
                        SqlitePublicProjectionStartupResult.Status
                                .CATCH_UP_FAILED,
                        results,
                        result.failure()
                );
            }
            return catchUp(ordered, index + 1, results);
        });
    }

    private CompletionStage<SqlitePublicProjectionStartupResult> completed(
            SqlitePublicProjectionStartupResult.Status status,
            List<ProjectionCatchUpResult> results,
            Throwable failure
    ) {
        return CompletableFuture.completedFuture(
                new SqlitePublicProjectionStartupResult(
                        status,
                        results,
                        failure
                )
        );
    }

    private Throwable readFailure(
            String authority,
            PersistenceReadResult<?> read
    ) {
        if (read instanceof PersistenceReadResult.Failed<?> failed) {
            return new IllegalStateException(
                    failed.failure().code(),
                    failed.failure().cause()
            );
        }
        return new IllegalStateException(
                "canonical_" + authority + "_rebuild_read_absent"
        );
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

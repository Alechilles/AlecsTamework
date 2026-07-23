package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.internal.CompanionProfileObserverProjection;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterProjectionIndex;
import com.alechilles.alecstamework.companion.command.CommandRosterProjectionSeed;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionIndex;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.OwnerPopulationProjectionIndex;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupProjectionIndex;
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
    private final CoopResidencyProjectionIndex coopIndex;
    private final OwnerPopulationProjectionIndex ownerPopulationIndex;
    private final PopulationGroupProjectionIndex populationGroupIndex;
    private final CommandRosterProjectionIndex commandRosterIndex;
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
        this.ownerPopulationIndex = new OwnerPopulationProjectionIndex();
        this.populationGroupIndex = new PopulationGroupProjectionIndex();
        this.commandRosterIndex = new CommandRosterProjectionIndex();
        this.consumers = Map.of(
                profileObserver.consumerId(),
                profileObserver,
                coopIndex.consumerId(),
                coopIndex,
                ownerPopulationIndex.consumerId(),
                ownerPopulationIndex,
                populationGroupIndex.consumerId(),
                populationGroupIndex,
                commandRosterIndex.consumerId(),
                commandRosterIndex
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

    @Nonnull
    OwnerPopulationProjectionIndex ownerPopulationIndex() {
        return ownerPopulationIndex;
    }

    @Nonnull
    PopulationGroupProjectionIndex populationGroupIndex() {
        return populationGroupIndex;
    }

    @Nonnull
    CommandRosterProjectionIndex commandRosterIndex() {
        return commandRosterIndex;
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
            @Nonnull SqliteCompanionCoopReader coopReader,
            @Nonnull SqliteCompanionLifecycleReader lifecycleReader,
            @Nonnull SqlitePopulationGroupReader populationGroupReader,
            @Nonnull SqliteCommandRosterReader commandRosterReader
    ) {
        if (coopReader == null || lifecycleReader == null
                || populationGroupReader == null
                || commandRosterReader == null) {
            throw new IllegalArgumentException(
                    "Canonical projection readers are required"
            );
        }
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
            return rebuildOwnerPopulation(
                    lifecycleReader,
                    populationGroupReader,
                    commandRosterReader
            );
        });
    }

    private CompletionStage<SqlitePublicProjectionStartupResult>
    rebuildOwnerPopulation(
            SqliteCompanionLifecycleReader lifecycleReader,
            SqlitePopulationGroupReader populationGroupReader,
            SqliteCommandRosterReader commandRosterReader
    ) {
        return lifecycleReader.findAll().thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    List<CompanionLifecycle>> found)) {
                return completed(
                        SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_READ_FAILED,
                        List.of(),
                        readFailure("lifecycle", read)
                );
            }
            try {
                ownerPopulationIndex.rebuild(found.value());
            } catch (Throwable failure) {
                return completed(
                        SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_REBUILD_FAILED,
                        List.of(),
                        failure
                );
            }
            return rebuildPopulationGroups(
                    populationGroupReader,
                    commandRosterReader,
                    found.value()
            );
        });
    }

    private CompletionStage<SqlitePublicProjectionStartupResult>
    rebuildPopulationGroups(
            SqlitePopulationGroupReader populationGroupReader,
            SqliteCommandRosterReader commandRosterReader,
            List<CompanionLifecycle> lifecycles
    ) {
        return populationGroupReader.findAllAssignments().thenCompose(
                read -> {
                    if (!(read instanceof PersistenceReadResult.Found<
                            List<PopulationGroupAssignment>> found)) {
                        return completed(
                                SqlitePublicProjectionStartupResult.Status
                                        .CANONICAL_READ_FAILED,
                                List.of(),
                                readFailure("population_groups", read)
                        );
                    }
                    try {
                        populationGroupIndex.rebuild(
                                found.value(), lifecycles
                        );
                    } catch (Throwable failure) {
                        return completed(
                                SqlitePublicProjectionStartupResult.Status
                                        .CANONICAL_REBUILD_FAILED,
                                List.of(),
                                failure
                        );
                    }
                    return rebuildCommandRosters(
                            commandRosterReader, lifecycles
                    );
                }
        );
    }

    private CompletionStage<SqlitePublicProjectionStartupResult>
    rebuildCommandRosters(
            SqliteCommandRosterReader commandRosterReader,
            List<CompanionLifecycle> lifecycles
    ) {
        return commandRosterReader.findAllRosters().thenCompose(
                rosterRead -> {
                    if (!(rosterRead instanceof PersistenceReadResult.Found<
                            List<CommandRoster>> foundRosters)) {
                        return completed(
                                SqlitePublicProjectionStartupResult.Status
                                        .CANONICAL_READ_FAILED,
                                List.of(),
                                readFailure(
                                        "command_rosters", rosterRead
                                )
                        );
                    }
                    return commandRosterReader
                            .findAllProjectionSeeds()
                            .thenCompose(seedRead -> rebuildCommandRosters(
                                    foundRosters.value(),
                                    seedRead,
                                    lifecycles
                            ));
                }
        );
    }

    private CompletionStage<SqlitePublicProjectionStartupResult>
    rebuildCommandRosters(
            List<CommandRoster> rosters,
            PersistenceReadResult<List<CommandRosterProjectionSeed>>
                    seedRead,
            List<CompanionLifecycle> lifecycles
    ) {
        if (!(seedRead instanceof PersistenceReadResult.Found<
                List<CommandRosterProjectionSeed>> foundSeeds)) {
            return completed(
                    SqlitePublicProjectionStartupResult.Status
                            .CANONICAL_READ_FAILED,
                    List.of(),
                    readFailure("command_roster_seeds", seedRead)
            );
        }
        try {
            commandRosterIndex.rebuild(
                    rosters, foundSeeds.value(), lifecycles
            );
        } catch (Throwable failure) {
            return completed(
                    SqlitePublicProjectionStartupResult.Status
                            .CANONICAL_REBUILD_FAILED,
                    List.of(),
                    failure
            );
        }
        return catchUp(all(), 0, new ArrayList<>());
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

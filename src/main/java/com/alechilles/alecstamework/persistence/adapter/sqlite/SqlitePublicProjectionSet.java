package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.internal.CompanionProfileObserverProjection;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptCooldownIndex;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterProjectionIndex;
import com.alechilles.alecstamework.companion.command.CommandRosterProjectionSeed;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonProjectionIndex;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionIndex;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionIndex;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.population.OwnerPopulationProjectionIndex;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupProjectionIndex;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningProjectionIndex;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.facade
        .ReplacementPublicApiEventSink;
import com.alechilles.alecstamework.persistence.facade
        .ReplacementPublicSemanticEventProjection;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionCatchUpResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection
        .ContextualProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionPublicationContext;
import com.alechilles.alecstamework.persistence.projection.ProjectionPublicationScheduler;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
/** Registry-checked projection composition shared by public work and recovery. */
final class SqlitePublicProjectionSet {
    private final ProjectionCoordinator coordinator;
    private final ProjectionPublicationScheduler publicationScheduler;
    private final CompanionProfileObserverProjection profileObserver;
    private final ReplacementPublicSemanticEventProjection
            publicEventObserver;
    private final CoopResidencyProjectionIndex coopIndex;
    private final OwnerPopulationProjectionIndex ownerPopulationIndex;
    private final PopulationGroupProjectionIndex populationGroupIndex;
    private final CommandRosterProjectionIndex commandRosterIndex;
    private final TimedSummonProjectionIndex timedSummonIndex;
    private final SqliteTimedSummonLeaseReader timedSummonReader;
    private final ProvisioningProjectionIndex provisioningIndex;
    private final SqliteProvisioningReader provisioningReader;
    private final ProfileExtensionProjectionIndex extensionIndex;
    private final SqliteProfileExtensionReader extensionReader;
    private final CaptureAttemptCooldownIndex captureCooldownIndex;
    private final SqliteProjectionConsumerSet consumers;
    SqlitePublicProjectionSet(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull LongSupplier clock,
            @Nonnull Consumer<NpcProfileChangedEvent> profileListener,
            @Nonnull ReplacementPublicApiEventSink publicEventSink
    ) {
        this(
                registry,
                kernel,
                clock,
                profileListener,
                publicEventSink,
                PersistenceThroughputMetrics.NO_OP
        );
    }

    SqlitePublicProjectionSet(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull LongSupplier clock,
            @Nonnull Consumer<NpcProfileChangedEvent> profileListener,
            @Nonnull ReplacementPublicApiEventSink publicEventSink,
            @Nonnull PersistenceThroughputMetrics throughputMetrics
    ) {
        if (registry == null || kernel == null || clock == null
                || profileListener == null || publicEventSink == null
                || throughputMetrics == null) {
            throw new IllegalArgumentException(
                    "Public projection dependencies are required"
            );
        }
        this.coordinator = new ProjectionCoordinator(
                new SqliteProjectionGateway(
                        kernel.reads(),
                        kernel.units(),
                        throughputMetrics
                ),
                ProjectionRetryPolicy.DEFAULT,
                clock
        );
        this.publicationScheduler =
                new ProjectionPublicationScheduler(
                        this.coordinator, throughputMetrics
                );
        this.profileObserver =
                new CompanionProfileObserverProjection(profileListener);
        this.publicEventObserver =
                new ReplacementPublicSemanticEventProjection(
                        publicEventSink, clock
                );
        this.coopIndex = new CoopResidencyProjectionIndex();
        this.ownerPopulationIndex = new OwnerPopulationProjectionIndex();
        this.populationGroupIndex = new PopulationGroupProjectionIndex();
        this.commandRosterIndex = new CommandRosterProjectionIndex();
        this.timedSummonIndex = new TimedSummonProjectionIndex();
        this.timedSummonReader =
                new SqliteTimedSummonLeaseReader(kernel.reads());
        this.provisioningIndex = new ProvisioningProjectionIndex();
        this.provisioningReader =
                new SqliteProvisioningReader(kernel.reads());
        this.extensionIndex = new ProfileExtensionProjectionIndex();
        this.extensionReader =
                new SqliteProfileExtensionReader(kernel.reads());
        this.captureCooldownIndex = new CaptureAttemptCooldownIndex();
        this.consumers = new SqliteProjectionConsumerSet(
                registry,
                List.<ProjectionConsumer>of(
                profileObserver,
                coopIndex,
                ownerPopulationIndex,
                populationGroupIndex,
                commandRosterIndex,
                timedSummonIndex,
                provisioningIndex,
                extensionIndex,
                captureCooldownIndex,
                publicEventObserver
        ));
    }
    @Nonnull
    ProjectionPublicationScheduler publicationScheduler() {
        return publicationScheduler;
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
    @Nonnull
    TimedSummonProjectionIndex timedSummonIndex() {
        return timedSummonIndex;
    }
    @Nonnull
    ProvisioningProjectionIndex provisioningIndex() {
        return provisioningIndex;
    }
    @Nonnull
    CompanionProfileObserverProjection profileIndex() {
        return profileObserver;
    }
    @Nonnull
    ProfileExtensionProjectionIndex extensionIndex() {
        return extensionIndex;
    }
    @Nonnull
    CaptureAttemptCooldownIndex captureCooldownIndex() {
        return captureCooldownIndex;
    }

    /** Resolves the exact required set from the operation owner's descriptor. */
    @Nonnull
    List<ProjectionConsumer> requiredFor(@Nonnull OperationKind operationKind) {
        return requiredFor(
                operationKind, ProjectionPublicationContext.LIVE_COMMIT
        );
    }

    /** Resolves a stable consumer set bound to its publication origin. */
    @Nonnull
    List<ProjectionConsumer> requiredFor(
            @Nonnull OperationKind operationKind,
            @Nonnull ProjectionPublicationContext context
    ) {
        if (operationKind == null) {
            throw new IllegalArgumentException("Operation kind is required");
        }
        List<ProjectionConsumer> required =
                consumers.requiredFor(operationKind);
        return context == ProjectionPublicationContext.LIVE_COMMIT
                ? required
                : ContextualProjectionConsumer.bind(required, context);
    }

    @Nonnull
    List<ProjectionConsumer> all() {
        return consumers.all();
    }

    /** Rebuilds canonical indexes before catching every declared consumer up. */
    @Nonnull
    CompletionStage<SqlitePublicProjectionStartupResult> rebuildAndCatchUp(
            @Nonnull SqliteCompanionProfileReader profileReader,
            @Nonnull SqliteCompanionCoopReader coopReader,
            @Nonnull SqliteCompanionLifecycleReader lifecycleReader,
            @Nonnull SqlitePopulationGroupReader populationGroupReader,
            @Nonnull SqliteCommandRosterReader commandRosterReader
    ) {
        if (profileReader == null || coopReader == null
                || lifecycleReader == null
                || populationGroupReader == null
                || commandRosterReader == null) {
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
            return rebuildCoop(
                    coopReader,
                    lifecycleReader,
                    populationGroupReader,
                    commandRosterReader
            );
        });
    }

    private CompletionStage<SqlitePublicProjectionStartupResult> rebuildCoop(
            SqliteCompanionCoopReader coopReader,
            SqliteCompanionLifecycleReader lifecycleReader,
            SqlitePopulationGroupReader populationGroupReader,
            SqliteCommandRosterReader commandRosterReader
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
        return timedSummonReader.findAll().thenCompose(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    List<TimedSummonLease>> found)) {
                return completed(
                        SqlitePublicProjectionStartupResult.Status
                                .CANONICAL_READ_FAILED,
                        List.of(),
                        readFailure("timed_summon_leases", read)
                );
            }
            try {
                timedSummonIndex.rebuild(
                        found.value(), rosters, lifecycles
                );
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
                provisioningReader,
                provisioningIndex,
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
}

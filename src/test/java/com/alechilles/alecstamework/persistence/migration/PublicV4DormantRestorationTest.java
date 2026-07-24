package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.restoration.RestorationProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionIdentityStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionLifecycleStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionProfileReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionRestorationOperations;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionSnapshotStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEvidenceReader;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationPublisher;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionGateway;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.items.persistence.TameworkRestorationSnapshotResolver;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Released-v4 death and lost artifacts restore through the replacement protocol unchanged. */
class PublicV4DormantRestorationTest {
    private static final ProfileId DEAD =
            ProfileId.parse("20000000-0000-0000-0000-000000000003");
    private static final ProfileId LOST =
            ProfileId.parse("20000000-0000-0000-0000-000000000004");
    private static final NpcAlias DEAD_OLD =
            NpcAlias.parse("00000000-0000-0000-0000-000000000003");
    private static final NpcAlias LOST_OLD =
            NpcAlias.parse("00000000-0000-0000-0000-000000000004");
    private static final NpcAlias DEAD_TARGET =
            NpcAlias.parse("90000000-0000-0000-0000-000000000003");
    private static final NpcAlias LOST_TARGET =
            NpcAlias.parse("90000000-0000-0000-0000-000000000004");

    @TempDir
    Path tempDir;

    @Test
    void importsAndRestoresBothReleasedDormantArtifactKinds()
            throws Exception {
        Path source = tempDir.resolve("tamework.sqlite");
        Path target = tempDir.resolve("tamework-state.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql", source
        );
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                PublicImportResult.Imported.class,
                new PublicPersistenceImporter(() -> -7_000)
                        .importSource(source, target)
        );

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(target);
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        try {
            SqliteUnitOfWorkRunner units =
                    new SqliteUnitOfWorkRunner(writer, reads);
            SqliteOperationEngine engine = new SqliteOperationEngine(
                    new OperationDefinitionRegistry(List.of(
                            CompanionRestorationDefinition.INSTANCE
                    )),
                    units
            );
            SqliteCompanionRestorationOperations restorations =
                    new SqliteCompanionRestorationOperations(
                            engine,
                            new SqliteOperationPublisher(
                                    engine,
                                    new SqliteOperationEvidenceReader(reads),
                                    new ProjectionCoordinator(
                                            new SqliteProjectionGateway(
                                                    reads, units
                                            ),
                                            ProjectionRetryPolicy.DEFAULT,
                                            () -> -400
                                    ),
                                    () -> -400
                            ),
                            () -> -400,
                            List.of()
                    );
            SqliteCompanionProfileReader profiles =
                    new SqliteCompanionProfileReader(reads);

            restore(
                    connections,
                    profiles,
                    restorations,
                    3,
                    DEAD,
                    LifecycleState.DEAD_REVIVABLE,
                    new SnapshotKind("death"),
                    DEAD_OLD,
                    DEAD_TARGET
            );
            restore(
                    connections,
                    profiles,
                    restorations,
                    4,
                    LOST,
                    LifecycleState.LOST,
                    new SnapshotKind("lost"),
                    LOST_OLD,
                    LOST_TARGET
            );
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private void restore(
            SqliteConnectionFactory connections,
            SqliteCompanionProfileReader profiles,
            SqliteCompanionRestorationOperations restorations,
            int suffix,
            ProfileId profile,
            LifecycleState state,
            SnapshotKind kind,
            NpcAlias oldAlias,
            NpcAlias targetAlias
    ) throws Exception {
        CompanionLifecycle before;
        CompanionSnapshot snapshot;
        try (Connection connection = connections.openReadConnection()) {
            before = new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(profile)
                    .orElseThrow();
            snapshot = new SqliteCompanionSnapshotStore(connection)
                    .findCurrent(profile, kind)
                    .orElseThrow();
        }
        assertEquals(state, before.state());
        PersistenceReadResult.Found<CompanionProfileReadModel> found =
                assertInstanceOf(
                        PersistenceReadResult.Found.class,
                        profiles.findByProfile(profile).toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                );
        TameworkRestorationSnapshotResolver.Resolution resolution =
                new TameworkRestorationSnapshotResolver()
                        .resolve(found.value(), snapshot);
        TameworkRestorationSnapshotResolver.Resolution.Resolved resolved =
                assertInstanceOf(
                        TameworkRestorationSnapshotResolver.Resolution
                                .Resolved.class,
                        resolution,
                        () -> "Imported dormant snapshot did not resolve: "
                                + resolution
                );
        RestorationProjection projection = resolved.projection();
        assertEquals(oldAlias, projection.sourceAlias());

        OperationId operationId = OperationId.parse(String.format(
                "60000000-0000-0000-0000-%012d", suffix
        ));
        OperationWorkflowResult result = restorations.submit(
                operationId,
                new IdempotencyKey("public-v4-restoration-" + suffix),
                new CompanionRestorationRequest(
                        profile,
                        before.revision(),
                        state,
                        snapshot,
                        projection,
                        targetAlias,
                        new CompanionSpawnPlacement(
                                "restored-world", -12.5, -63.05, -4.5,
                                -0.25f, -1.5f, -0.5f
                        ),
                        "public-v4-spawn-receipt-" + suffix,
                        -600
                ),
                (request, operation) -> {
                    try (Connection connection =
                                 connections.openReadConnection()) {
                        assertEquals(
                                CompanionAlias.State.LEASED,
                                new SqliteCompanionIdentityStore(connection)
                                        .resolveAlias(targetAlias)
                                        .orElseThrow()
                                        .state()
                        );
                    }
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                }
        ).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        try (Connection connection = connections.openReadConnection()) {
            CompanionLifecycle active =
                    new SqliteCompanionLifecycleStore(connection)
                            .findByProfile(profile)
                            .orElseThrow();
            SqliteCompanionIdentityStore identities =
                    new SqliteCompanionIdentityStore(connection);
            assertEquals(LifecycleState.ACTIVE, active.state());
            assertEquals(
                    LifecycleLocation.liveEntity(
                            targetAlias.toString(), "restored-world"
                    ),
                    active.location()
            );
            assertEquals(
                    CompanionAlias.State.CURRENT,
                    identities.resolveAlias(targetAlias)
                            .orElseThrow().state()
            );
            assertEquals(
                    CompanionAlias.State.RETIRED,
                    identities.resolveAlias(oldAlias)
                            .orElseThrow().state()
            );
            assertTrue(new SqliteCompanionSnapshotStore(connection)
                    .findCurrent(profile, kind).isEmpty());
        }
    }
}

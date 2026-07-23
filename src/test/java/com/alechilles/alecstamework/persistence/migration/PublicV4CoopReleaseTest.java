package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionIndex;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionCoopReleaseOperations;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionCoopStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionIdentityStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionLifecycleStore;
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

/** Released-v4 coop evidence imports under a normalized key and releases unchanged. */
class PublicV4CoopReleaseTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000005");
    private static final NpcAlias OLD_ALIAS =
            NpcAlias.parse("00000000-0000-0000-0000-000000000005");
    private static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("90000000-0000-0000-0000-000000000005");
    private static final CoopSlotKey SLOT =
            new CoopSlotKey("world-a", "fixture-coop", 10, 64, 20, 0);

    @TempDir
    Path tempDir;

    @Test
    void importsAndReleasesRepresentativePublicCoopArtifact()
            throws Exception {
        Path source = tempDir.resolve("tamework.sqlite");
        Path target = tempDir.resolve("tamework-state.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql", source
        );
        assertInstanceOf(
                PublicImportResult.Imported.class,
                new PublicPersistenceImporter(() -> -7_000)
                        .importSource(source, target)
        );

        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(target);
        CoopOccupancy occupancy;
        CompanionLifecycle lifecycle;
        CompanionSnapshot snapshot;
        try (Connection connection = connections.openReadConnection()) {
            SqliteCompanionCoopStore coops =
                    new SqliteCompanionCoopStore(connection);
            occupancy = coops.findAllOccupancies().getFirst();
            lifecycle = new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE).orElseThrow();
            snapshot = new SqliteCompanionSnapshotStore(connection)
                    .findById(occupancy.residency().snapshotId())
                    .orElseThrow();
        }
        assertEquals(SLOT, occupancy.slot().key());
        assertEquals(1, occupancy.slot().residencyRevision());
        assertEquals(LifecycleState.COOP, lifecycle.state());
        assertEquals(SLOT.toString(), lifecycle.location().key());

        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections);
        try {
            SqliteUnitOfWorkRunner units =
                    new SqliteUnitOfWorkRunner(writer, reads);
            SqliteOperationEngine engine = new SqliteOperationEngine(
                    new OperationDefinitionRegistry(List.of(
                            CompanionCoopReleaseDefinition.INSTANCE
                    )),
                    units
            );
            CoopResidencyProjectionIndex index =
                    new CoopResidencyProjectionIndex();
            index.rebuild(List.of(occupancy));
            SqliteCompanionCoopReleaseOperations releases =
                    new SqliteCompanionCoopReleaseOperations(
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
                            List.of(index)
                    );

            OperationWorkflowResult result = releases.submit(
                    OperationId.parse(
                            "60000000-0000-0000-0000-000000000005"
                    ),
                    new IdempotencyKey("public-v4-coop-release"),
                    new CompanionCoopReleaseRequest(
                            PROFILE,
                            lifecycle.revision(),
                            occupancy.residency(),
                            snapshot,
                            TARGET_ALIAS,
                            new CompanionSpawnPlacement(
                                    "restored-world", -12.5, -63.05, -4.5,
                                    -0.25f, -1.5f, -0.5f
                            ),
                            "public-v4-coop-spawn-receipt",
                            -600
                    ),
                    (request, operation) -> LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed()
            ).completion().toCompletableFuture().get(
                    20, TimeUnit.SECONDS
            );

            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    result.status()
            );
            assertTrue(index.findBySlot(SLOT).isEmpty());
            assertReleased(connections);
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    private void assertReleased(SqliteConnectionFactory connections)
            throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            CompanionLifecycle active =
                    new SqliteCompanionLifecycleStore(connection)
                            .findByProfile(PROFILE).orElseThrow();
            SqliteCompanionIdentityStore identities =
                    new SqliteCompanionIdentityStore(connection);
            SqliteCompanionCoopStore coops =
                    new SqliteCompanionCoopStore(connection);
            assertEquals(LifecycleState.ACTIVE, active.state());
            assertEquals(
                    LifecycleLocation.liveEntity(
                            TARGET_ALIAS.toString(), "restored-world"
                    ),
                    active.location()
            );
            assertEquals(
                    CompanionAlias.State.CURRENT,
                    identities.resolveAlias(TARGET_ALIAS)
                            .orElseThrow().state()
            );
            assertEquals(
                    CompanionAlias.State.RETIRED,
                    identities.resolveAlias(OLD_ALIAS)
                            .orElseThrow().state()
            );
            assertTrue(coops.findResidencyBySlot(SLOT).isEmpty());
            assertEquals(2, coops.findSlot(SLOT)
                    .orElseThrow().residencyRevision());
        }
    }
}

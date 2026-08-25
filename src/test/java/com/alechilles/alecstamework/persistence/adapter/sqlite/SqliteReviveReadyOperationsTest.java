package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.revival.ReviveReadyDefinition;
import com.alechilles.alecstamework.companion.revival.ReviveReadyRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Codec;
import com.alechilles.alecstamework.items.persistence.DeathSnapshotV2Payload;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupAction;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Verifies the generic revive-ready mutation preserves the complete death snapshot. */
class SqliteReviveReadyOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("20000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID =
            UUID.fromString("25000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteReviveReadyOperations operations;
    private CompanionSnapshot originalSnapshot;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -1_000L).initialize();
        seedDeadProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        PersistenceStartupCoordinator admission = readyAdmission();
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(ReviveReadyDefinition.INSTANCE)
                ),
                units,
                admission
        );
        operations = new SqliteReviveReadyOperations(
                new SqliteDatabaseOperationCoordinator(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -250L
                        ),
                        () -> -250L
                ),
                List.of()
        );
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown(Duration.ofSeconds(5));
        }
        if (reads != null) {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void markingDeadProfileReadyReplacesCurrentSnapshotAndPublishesChange()
            throws Exception {
        OperationWorkflowResult result = operations.submit(
                OperationId.parse("30000000-0000-0000-0000-000000000001"),
                new IdempotencyKey("revive-ready-test"),
                new ReviveReadyRequest(PROFILE, OWNER, -300L)
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(1, result.events().size());

        CompanionSnapshot replacement = currentDeathSnapshot();
        DeathSnapshotV2Payload death = new DeathSnapshotV2Codec().decode(
                replacement.payloadJson()
        );
        assertNotEquals(originalSnapshot.snapshotId(), replacement.snapshotId());
        assertEquals(-300L, death.respawnAvailableAtMs());
        assertEquals(-500L, death.diedAtMs());
        assertEquals(
                new DeathSnapshotV2Codec().decode(originalSnapshot.payloadJson())
                        .fullStateJson(),
                death.fullStateJson()
        );
    }

    private PersistenceStartupCoordinator readyAdmission() {
        EnumMap<PersistenceStartupNode, PersistenceStartupAction> actions =
                new EnumMap<>(PersistenceStartupNode.class);
        for (PersistenceStartupNode node : PersistenceStartupNode.values()) {
            actions.put(node, () -> CompletableFuture.completedFuture(
                    PersistenceStartupAction.Result.COMPLETE
            ));
        }
        PersistenceStartupCoordinator admission =
                new PersistenceStartupCoordinator(
                        PublicPersistenceFeatureRegistry.create(),
                        Map.copyOf(actions)
                );
        admission.advance().toCompletableFuture().join();
        return admission;
    }

    @Test
    void rejectsOwnerThatNoLongerOwnsTheProfile() throws Exception {
        OperationWorkflowResult result = operations.submit(
                OperationId.parse("30000000-0000-0000-0000-000000000002"),
                new IdempotencyKey("revive-ready-wrong-owner"),
                new ReviveReadyRequest(
                        PROFILE,
                        OwnerId.parse("20000000-0000-0000-0000-000000000002"),
                        -300L
                )
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertNotEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(originalSnapshot.snapshotId(), currentDeathSnapshot().snapshotId());
    }

    @Test
    void rejectsProfileThatIsNoLongerLinked() throws Exception {
        try (var connection = connections.openWriterConnection()) {
            new SqliteCompanionToolLinkStore(connection).replace(PROFILE, List.of());
        }

        OperationWorkflowResult result = operations.submit(
                OperationId.parse("30000000-0000-0000-0000-000000000003"),
                new IdempotencyKey("revive-ready-unlinked"),
                new ReviveReadyRequest(PROFILE, OWNER, -300L)
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertNotEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(originalSnapshot.snapshotId(), currentDeathSnapshot().snapshotId());
    }

    private void seedDeadProfile() throws Exception {
        DeathSnapshotV2Payload death = DeathSnapshotV2Payload.capture(
                fullState(),
                -500L,
                -100L,
                DeathSnapshotV2Payload.DeathCauseKind.ENVIRONMENT,
                "Lava"
        );
        String payload = new DeathSnapshotV2Codec().encode(death);
        originalSnapshot = new CompanionSnapshot(
                SnapshotId.parse("40000000-0000-0000-0000-000000000001"),
                PROFILE,
                TameworkSnapshotCodecs.DEATH,
                2,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                true,
                -500L
        );
        try (var connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE,
                    "Companion",
                    "role",
                    null,
                    null,
                    "world",
                    -1_000L,
                    -1_000L,
                    -1_000L,
                    0L
            ));
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE,
                    OWNER,
                    LifecycleState.DEAD_REVIVABLE,
                    LifecycleLocation.none(),
                    LifecycleRevision.INITIAL,
                    null,
                    -500L,
                    ReconciliationGeneration.INITIAL,
                    null
            ));
            transaction.snapshots().replaceCurrent(originalSnapshot);
            transaction.toolLinks().link(new CompanionToolLink(
                    PROFILE,
                    TOOL_ID,
                    "command",
                    -500L,
                    -500L
            ));
            connection.commit();
        }
    }

    private CompanionSnapshot currentDeathSnapshot() throws Exception {
        try (var connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection).findCurrent(
                    PROFILE,
                    TameworkSnapshotCodecs.DEATH
            ).orElseThrow();
        }
    }

    private CoopResidentStateSnapshot fullState() {
        return new CoopResidentStateSnapshot(
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                null,
                -1,
                "tamework_test",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0.75,
                -700L
        );
    }
}

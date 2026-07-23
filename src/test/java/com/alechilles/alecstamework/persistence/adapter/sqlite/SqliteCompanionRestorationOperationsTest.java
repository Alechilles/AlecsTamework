package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationEventCodec;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationLiveBoundary;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationOutcome;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end alias lease, live receipt, durable restoration, and replay tests. */
class SqliteCompanionRestorationOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final SnapshotId SNAPSHOT_ID =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");
    private static final String SNAPSHOT_JSON = "{\"health\":100}";

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionRestorationOperations restorations;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seedDormantProfile(LifecycleState.DEAD_REVIVABLE);
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionRestorationDefinition.INSTANCE
                )),
                units
        );
        restorations = new SqliteCompanionRestorationOperations(
                engine,
                new SqliteOperationPublisher(
                        engine,
                        new SqliteOperationEvidenceReader(reads),
                        new ProjectionCoordinator(
                                new SqliteProjectionGateway(reads, units),
                                ProjectionRetryPolicy.DEFAULT,
                                () -> -400
                        ),
                        () -> -400
                ),
                () -> -400,
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
    void aliasLeaseAndLifecycleFenceAreDurableBeforeEntityInsertion()
            throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                1,
                (restoration, operation) -> {
                    liveCalls.incrementAndGet();
                    assertEquals(
                            OperationPhase.LIVE_APPLYING,
                            operation.phase()
                    );
                    CompanionAlias lease = alias(TARGET_ALIAS);
                    assertEquals(CompanionAlias.State.LEASED, lease.state());
                    assertEquals(
                            operation.operationId(),
                            lease.leaseOperationId()
                    );
                    CompanionLifecycle fenced = lifecycle();
                    assertEquals(new LifecycleRevision(2), fenced.revision());
                    assertEquals(
                            operation.operationId(),
                            fenced.activeOperationId()
                    );
                    assertEquals(
                            LifecycleState.DEAD_REVIVABLE,
                            fenced.state()
                    );
                    assertTrue(snapshot().current());
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                }
        );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(1, liveCalls.get());
        CompanionRestorationOutcome outcome =
                CompanionRestorationEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                );
        assertEquals(TARGET_ALIAS, outcome.targetAlias());
        CompanionLifecycle active = lifecycle();
        assertEquals(LifecycleState.ACTIVE, active.state());
        assertEquals(new LifecycleRevision(3), active.revision());
        assertEquals(
                LifecycleLocation.liveEntity(
                        TARGET_ALIAS.toString(), "world-two"
                ),
                active.location()
        );
        assertEquals(OWNER, active.ownerId());
        assertNull(active.activeOperationId());
        assertEquals(
                CompanionAlias.State.CURRENT,
                alias(TARGET_ALIAS).state()
        );
        assertEquals(
                CompanionAlias.State.RETIRED,
                alias(SOURCE_ALIAS).state()
        );
        assertTrue(!snapshot().current());
    }

    @Test
    void retryAndPublishedReplayNeverInsertASecondEntity()
            throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger insertions = new AtomicInteger();
        CompanionRestorationLiveBoundary boundary =
                (restoration, operation) -> {
                    if (resolutions.incrementAndGet() == 1) {
                        return LiveOperationResult.retryable(
                                "target_world_temporarily_unavailable",
                                null
                        ).completed();
                    }
                    insertions.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                };

        OperationWorkflowResult first = submit(2, boundary);
        OperationWorkflowResult second = submit(2, boundary);
        OperationWorkflowResult replay = submit(2, boundary);

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                first.status()
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, second.status());
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(2, resolutions.get());
        assertEquals(1, insertions.get());
    }

    @Test
    void entityAbsenceIsRetryableAndCannotFinalizeRestoration()
            throws Exception {
        OperationWorkflowResult result = submit(
                3,
                (restoration, operation) -> LiveOperationResult.retryable(
                        "spawn_receipt_not_found",
                        null
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                result.status()
        );
        assertEquals(
                LifecycleState.DEAD_REVIVABLE,
                lifecycle().state()
        );
        assertNotNull(lifecycle().activeOperationId());
        assertEquals(
                CompanionAlias.State.LEASED,
                alias(TARGET_ALIAS).state()
        );
        assertTrue(snapshot().current());
    }

    @Test
    void ambiguousInsertionQuarantinesOnlyOperationAndProfile()
            throws Exception {
        OperationWorkflowResult result = submit(
                4,
                (restoration, operation) -> LiveOperationResult.unknown(
                        "spawn_receipt_read_failed",
                        null
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                result.status()
        );
        assertEquals(OperationPhase.UNKNOWN, result.operation().phase());
        try (Connection connection = connections.openReadConnection()) {
            SqliteIncidentStore incidents = new SqliteIncidentStore(connection);
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.operation(operationId(4))
                    ).orElseThrow().state()
            );
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.profile(PROFILE)
                    ).orElseThrow().state()
            );
            assertTrue(
                    incidents.findQuarantine(OperationScope.owner(OWNER))
                            .isEmpty()
            );
            assertTrue(
                    incidents.findQuarantine(OperationScope.global())
                            .isEmpty()
            );
        }
        assertEquals(
                LifecycleState.DEAD_REVIVABLE,
                lifecycle().state()
        );
        assertEquals(
                CompanionAlias.State.LEASED,
                alias(TARGET_ALIAS).state()
        );
        assertTrue(snapshot().current());
    }

    @Test
    void durableCommitWaitsForAsynchronousWorldThreadEvidence()
            throws Exception {
        CompletableFuture<LiveOperationResult> live =
                new CompletableFuture<>();
        CountDownLatch invoked = new CountDownLatch(1);

        SqliteCompanionRestorationOperations.Submission submission =
                restorations.submit(
                        operationId(5),
                        new IdempotencyKey("restoration-5"),
                        restorationRequest(),
                        (restoration, operation) -> {
                            invoked.countDown();
                            return live;
                        }
                );

        assertTrue(invoked.await(10, TimeUnit.SECONDS));
        assertFalse(submission.completion().toCompletableFuture().isDone());
        assertEquals(
                CompanionAlias.State.LEASED,
                alias(TARGET_ALIAS).state()
        );
        assertEquals(
                LifecycleState.DEAD_REVIVABLE,
                lifecycle().state()
        );

        live.complete(LiveOperationResult.confirmed(
                "spawn_receipt_confirmed"
        ));
        OperationWorkflowResult result = submission.completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
    }

    private OperationWorkflowResult submit(
            int number,
            CompanionRestorationLiveBoundary boundary
    ) throws Exception {
        SqliteCompanionRestorationOperations.Submission submission =
                restorations.submit(
                        operationId(number),
                        new IdempotencyKey("restoration-" + number),
                        restorationRequest(),
                        boundary
                );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private CompanionRestorationRequest restorationRequest() {
        return new CompanionRestorationRequest(
                PROFILE,
                new LifecycleRevision(1),
                LifecycleState.DEAD_REVIVABLE,
                sourceSnapshot(true),
                TARGET_ALIAS,
                new CompanionSpawnPlacement(
                        "world-two", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "spawn-receipt",
                -600
        );
    }

    private void seedDormantProfile(LifecycleState state) throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
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
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            CompanionLifecycle active = new CompanionLifecycle(
                    PROFILE,
                    OWNER,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(
                            SOURCE_ALIAS.toString(), "world"
                    ),
                    LifecycleRevision.INITIAL,
                    null,
                    -10_000,
                    new ReconciliationGeneration(4),
                    null
            );
            transaction.lifecycles().create(active);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO companion_alias(
                        npc_uuid, profile_id, alias_generation, alias_state,
                        lease_operation_id, mapped_at_ms, retired_at_ms
                    ) VALUES (?, ?, 0, 'CURRENT', NULL, ?, NULL)
                    """)) {
                statement.setString(1, SOURCE_ALIAS.toString());
                statement.setString(2, PROFILE.toString());
                statement.setLong(3, -11_000);
                statement.executeUpdate();
            }
            transaction.snapshots().replaceCurrent(sourceSnapshot(true));
            transaction.identities().retireAlias(SOURCE_ALIAS, -10_000);
            transaction.lifecycles().transition(new LifecycleTransition(
                    LifecycleRevision.INITIAL,
                    null,
                    new CompanionLifecycle(
                            PROFILE,
                            OWNER,
                            state,
                            LifecycleLocation.none(),
                            new LifecycleRevision(1),
                            null,
                            -10_000,
                            new ReconciliationGeneration(4),
                            null
                    )
            ));
            connection.commit();
        }
    }

    private CompanionSnapshot sourceSnapshot(boolean current) {
        return new CompanionSnapshot(
                SNAPSHOT_ID,
                PROFILE,
                DormantSourceEvidence.Kind.DEATH_COMPONENT.snapshotKind(),
                1,
                SNAPSHOT_JSON,
                Sha256Hash.ofUtf8(SNAPSHOT_JSON),
                LifecycleRevision.INITIAL,
                current,
                -10_000
        );
    }

    private CompanionLifecycle lifecycle() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE)
                    .orElseThrow();
        }
    }

    private CompanionAlias alias(NpcAlias alias) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(alias)
                    .orElseThrow();
        }
    }

    private CompanionSnapshot snapshot() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findById(SNAPSHOT_ID)
                    .orElseThrow();
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "60000000-0000-0000-0000-%012d",
                number
        ));
    }
}

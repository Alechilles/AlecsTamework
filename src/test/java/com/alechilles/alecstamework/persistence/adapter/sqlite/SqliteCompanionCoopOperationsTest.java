package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopCaptureSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionIndex;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistrationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
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

/** End-to-end shared-protocol tests for normalized coop registration, capture, and release. */
class SqliteCompanionCoopOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "fixture-coop", 10, 64, 20, 0);
    private static final SnapshotId SNAPSHOT_ID =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");
    private static final String SNAPSHOT_JSON = "{\"health\":100}";

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionCoopCaptureOperations captures;
    private SqliteCompanionCoopReleaseOperations releases;
    private CoopResidencyProjectionIndex index;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seedLiveProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CoopSlotRegistrationDefinition.INSTANCE,
                        CompanionCoopCaptureDefinition.INSTANCE,
                        CompanionCoopReleaseDefinition.INSTANCE
                )),
                units
        );
        ProjectionCoordinator projections = new ProjectionCoordinator(
                new SqliteProjectionGateway(reads, units),
                ProjectionRetryPolicy.DEFAULT,
                () -> -400
        );
        SqliteOperationEvidenceReader evidence =
                new SqliteOperationEvidenceReader(reads);
        SqliteOperationPublisher publisher = new SqliteOperationPublisher(
                engine, evidence, projections, () -> -400
        );
        index = new CoopResidencyProjectionIndex();
        List<CoopResidencyProjectionIndex> consumers = List.of(index);
        captures = new SqliteCompanionCoopCaptureOperations(
                engine, publisher, () -> -400, consumers
        );
        releases = new SqliteCompanionCoopReleaseOperations(
                engine, publisher, () -> -300, consumers
        );
        SqliteCoopSlotOperations slots = new SqliteCoopSlotOperations(
                new SqliteDatabaseOperationCoordinator(
                        engine, evidence, projections, () -> -500
                ),
                consumers
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                slots.submit(
                        operationId(90),
                        new IdempotencyKey("slot-registration"),
                        new CoopSlotRegistration(
                                CoopSlot.unoccupied(SLOT), -600
                        )
                ).completion().toCompletableFuture().get(
                        10, TimeUnit.SECONDS
                ).status()
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
    void captureAndReleaseCommitLifecycleDetailSnapshotAndProjectionAtomically()
            throws Exception {
        OperationWorkflowResult captured = capture(
                1,
                (request, operation) -> {
                    assertEquals(OperationPhase.LIVE_APPLYING, operation.phase());
                    assertEquals(new LifecycleRevision(1), lifecycle().revision());
                    assertEquals(
                            operation.operationId(),
                            slot().activeOperationId()
                    );
                    assertTrue(residency().isEmpty());
                    return LiveOperationResult.confirmed(
                            "retirement_receipt_confirmed"
                    ).completed();
                }
        );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, captured.status());
        assertEquals(4, captured.events().size());
        assertEquals(LifecycleState.COOP, lifecycle().state());
        assertEquals(new LifecycleRevision(2), lifecycle().revision());
        assertEquals(SLOT.toString(), lifecycle().location().key());
        assertNull(lifecycle().activeOperationId());
        assertEquals(1, slot().residencyRevision());
        assertFalse(slot().reserved());
        assertEquals(PROFILE, residency().orElseThrow().profileId());
        assertTrue(snapshot().current());
        assertEquals(
                PROFILE,
                index.findBySlot(SLOT).orElseThrow().residency().profileId()
        );

        OperationWorkflowResult released = release(
                2,
                (request, operation) -> {
                    assertEquals(OperationPhase.LIVE_APPLYING, operation.phase());
                    assertEquals(
                            CompanionAlias.State.LEASED,
                            alias(TARGET_ALIAS).state()
                    );
                    assertEquals(new LifecycleRevision(3), lifecycle().revision());
                    assertEquals(
                            operation.operationId(),
                            slot().activeOperationId()
                    );
                    assertTrue(residency().isPresent());
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                }
        );

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, released.status());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        assertEquals(new LifecycleRevision(4), lifecycle().revision());
        assertEquals(
                LifecycleLocation.liveEntity(
                        TARGET_ALIAS.toString(), "world-two"
                ),
                lifecycle().location()
        );
        assertEquals(2, slot().residencyRevision());
        assertFalse(slot().reserved());
        assertTrue(residency().isEmpty());
        assertFalse(snapshot().current());
        assertEquals(CompanionAlias.State.CURRENT, alias(TARGET_ALIAS).state());
        assertEquals(CompanionAlias.State.RETIRED, alias(SOURCE_ALIAS).state());
        assertTrue(index.findBySlot(SLOT).isEmpty());
    }

    @Test
    void retryAndPublishedReplayNeverDuplicateLiveMutation() throws Exception {
        AtomicInteger captureResolutions = new AtomicInteger();
        AtomicInteger captureMutations = new AtomicInteger();
        var captureBoundary =
                (com.alechilles.alecstamework.companion.coop
                        .CompanionCoopCaptureLiveBoundary) (request, operation) -> {
                    if (captureResolutions.incrementAndGet() == 1) {
                        return LiveOperationResult.retryable(
                                "source_receipt_not_found", null
                        ).completed();
                    }
                    captureMutations.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "retirement_receipt_confirmed"
                    ).completed();
                };

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                capture(3, captureBoundary).status()
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                capture(3, captureBoundary).status()
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                capture(3, captureBoundary).status()
        );
        assertEquals(2, captureResolutions.get());
        assertEquals(1, captureMutations.get());

        AtomicInteger releaseResolutions = new AtomicInteger();
        AtomicInteger releaseInsertions = new AtomicInteger();
        CompanionCoopReleaseRequest durableRelease = releaseRequest();
        var releaseBoundary =
                (com.alechilles.alecstamework.companion.coop
                        .CompanionCoopReleaseLiveBoundary) (request, operation) -> {
                    if (releaseResolutions.incrementAndGet() == 1) {
                        return LiveOperationResult.retryable(
                                "spawn_receipt_not_found", null
                        ).completed();
                    }
                    releaseInsertions.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "spawn_receipt_confirmed"
                    ).completed();
                };

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                release(4, durableRelease, releaseBoundary).status()
        );
        assertEquals(LifecycleState.COOP, lifecycle().state());
        assertNotNull(lifecycle().activeOperationId());
        assertTrue(slot().reserved());
        assertTrue(residency().isPresent());
        assertTrue(snapshot().current());
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                release(4, durableRelease, releaseBoundary).status()
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                release(4, durableRelease, releaseBoundary).status()
        );
        assertEquals(2, releaseResolutions.get());
        assertEquals(1, releaseInsertions.get());
    }

    @Test
    void staleCaptureConflictNeverCrossesTheLiveBoundary() throws Exception {
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                capture(5, confirmedRetirement()).status()
        );
        AtomicInteger calls = new AtomicInteger();

        OperationWorkflowResult stale = captures.submit(
                operationId(6),
                new IdempotencyKey("coop-capture-6"),
                captureRequest(),
                (request, operation) -> {
                    calls.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(OperationWorkflowResult.Status.PREPARE_FAILED, stale.status());
        assertEquals(0, calls.get());
        assertEquals(LifecycleState.COOP, lifecycle().state());
    }

    @Test
    void unknownCaptureQuarantinesOnlyOperationProfileAndCoop()
            throws Exception {
        OperationWorkflowResult result = capture(
                7,
                (request, operation) -> LiveOperationResult.unknown(
                        "retirement_receipt_read_failed", null
                ).completed()
        );

        assertEquals(OperationWorkflowResult.Status.LIVE_UNKNOWN, result.status());
        try (Connection connection = connections.openReadConnection()) {
            SqliteIncidentStore incidents = new SqliteIncidentStore(connection);
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.operation(operationId(7))
                    ).orElseThrow().state()
            );
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.profile(PROFILE)
                    ).orElseThrow().state()
            );
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.coop(SLOT.toString())
                    ).orElseThrow().state()
            );
            assertTrue(incidents.findQuarantine(OperationScope.owner(OWNER))
                    .isEmpty());
            assertTrue(incidents.findQuarantine(OperationScope.global())
                    .isEmpty());
        }
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        assertTrue(slot().reserved());
        assertTrue(residency().isEmpty());
    }

    private OperationWorkflowResult capture(
            int number,
            com.alechilles.alecstamework.companion.coop
                    .CompanionCoopCaptureLiveBoundary boundary
    ) throws Exception {
        return captures.submit(
                operationId(number),
                new IdempotencyKey("coop-capture-" + number),
                captureRequest(),
                boundary
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private OperationWorkflowResult release(
            int number,
            com.alechilles.alecstamework.companion.coop
                    .CompanionCoopReleaseLiveBoundary boundary
    ) throws Exception {
        return release(number, releaseRequest(), boundary);
    }

    private OperationWorkflowResult release(
            int number,
            CompanionCoopReleaseRequest request,
            com.alechilles.alecstamework.companion.coop
                    .CompanionCoopReleaseLiveBoundary boundary
    ) throws Exception {
        return releases.submit(
                operationId(number),
                new IdempotencyKey("coop-release-" + number),
                request,
                boundary
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private CompanionCoopCaptureRequest captureRequest() {
        return new CompanionCoopCaptureRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                SLOT,
                new CompanionSnapshot(
                        SNAPSHOT_ID,
                        PROFILE,
                        CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                        1,
                        SNAPSHOT_JSON,
                        Sha256Hash.ofUtf8(SNAPSHOT_JSON),
                        new LifecycleRevision(1),
                        true,
                        -500
                ),
                new CoopCaptureSourceEvidence(
                        SOURCE_ALIAS, "world", "retirement-receipt"
                ),
                -600
        );
    }

    private CompanionCoopReleaseRequest releaseRequest() throws Exception {
        return new CompanionCoopReleaseRequest(
                PROFILE,
                new LifecycleRevision(2),
                residency().orElseThrow(),
                snapshot(),
                TARGET_ALIAS,
                new CompanionSpawnPlacement(
                        "world-two", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "spawn-receipt",
                -350
        );
    }

    private com.alechilles.alecstamework.companion.coop
            .CompanionCoopCaptureLiveBoundary confirmedRetirement() {
        return (request, operation) -> LiveOperationResult.confirmed(
                "retirement_receipt_confirmed"
        ).completed();
    }

    private void seedLiveProfile() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqliteCompanionIdentityStore(connection).createProfile(
                    new CompanionIdentity(
                            PROFILE, "Companion", "role", null, null,
                            "world", -10_000, -10_000, -10_000, 0
                    )
            );
            new SqliteCompanionLifecycleStore(connection).create(
                    new CompanionLifecycle(
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
                    )
            );
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
            connection.commit();
        }
    }

    private CompanionLifecycle lifecycle() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE).orElseThrow();
        }
    }

    private CoopSlot slot() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionCoopStore(connection)
                    .findSlot(SLOT).orElseThrow();
        }
    }

    private java.util.Optional<CoopResidency> residency() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionCoopStore(connection)
                    .findResidencyBySlot(SLOT);
        }
    }

    private CompanionSnapshot snapshot() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findById(SNAPSHOT_ID).orElseThrow();
        }
    }

    private CompanionAlias alias(NpcAlias alias) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(alias).orElseThrow();
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(
                "40000000-0000-0000-0000-%012d".formatted(number)
        );
    }
}

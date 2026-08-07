package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureReleaseSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureReleaseLegacyRecoveryEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureReleaseModernRecoveryEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseEventCodec;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseOutcome;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end canonical and replay tests for captured-artifact release. */
class SqliteCompanionCaptureReleaseOperationsTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final NpcAlias NEWER_SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000003");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final OwnerId ASSIGNED_OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");
    private static final SnapshotId OTHER_SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqlitePersistenceKernel recoveryKernel;
    private SqliteCompanionCaptureReleaseOperations releases;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seedCapturedProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(
                writer,
                reads
        );
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionCaptureReleaseDefinition.INSTANCE
                )),
                units
        );
        releases = new SqliteCompanionCaptureReleaseOperations(
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
        if (recoveryKernel != null) {
            recoveryKernel.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void bothLiveReceiptsPrecedeAtomicCapturedToActiveCommit()
            throws Exception {
        AtomicInteger liveCalls = new AtomicInteger();
        assertEquals(new LifecycleRevision(1), lifecycle().revision());
        assertEquals(LifecycleState.CAPTURED, lifecycle().state());
        assertEquals(
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        SNAPSHOT.toString()
                ),
                lifecycle().location()
        );
        assertNull(lifecycle().activeOperationId());
        assertEquals(CompanionAlias.State.CURRENT, alias(SOURCE_ALIAS).state());
        assertEquals(request().sourceSnapshot(), snapshot());

        OperationWorkflowResult result = submit(
                1,
                (release, operation) -> {
                    liveCalls.incrementAndGet();
                    assertEquals(
                            OperationPhase.LIVE_APPLYING,
                            operation.phase()
                    );
                    CompanionLifecycle fenced = lifecycle();
                    assertEquals(LifecycleState.CAPTURED, fenced.state());
                    assertEquals(new LifecycleRevision(2), fenced.revision());
                    assertEquals(
                            operation.operationId(),
                            fenced.activeOperationId()
                    );
                    assertEquals(
                            CompanionAlias.State.LEASED,
                            alias(TARGET_ALIAS).state()
                    );
                    assertTrue(snapshot().current());
                    return LiveOperationResult.confirmed(
                            "capture_release_both_receipts_confirmed"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status(),
                String.valueOf(result.failure())
        );
        assertEquals(1, liveCalls.get());
        CompanionCaptureReleaseOutcome outcome =
                CompanionCaptureReleaseEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                );
        assertEquals("inventory-receipt", outcome.inventoryReceiptKey());
        assertEquals("spawn-receipt", outcome.spawnReceiptKey());
        CompanionLifecycle active = lifecycle();
        assertEquals(LifecycleState.ACTIVE, active.state());
        assertEquals(new LifecycleRevision(3), active.revision());
        assertEquals(
                LifecycleLocation.liveEntity(
                        TARGET_ALIAS.toString(),
                        "world-two"
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
    void crashStyleRetryAfterInventoryReceiptDoesNotConsumeOrSpawnTwice()
            throws Exception {
        AtomicBoolean inventoryReceipt = new AtomicBoolean();
        AtomicBoolean spawnReceipt = new AtomicBoolean();
        AtomicInteger inventoryWrites = new AtomicInteger();
        AtomicInteger spawnWrites = new AtomicInteger();
        CompanionCaptureReleaseLiveBoundary boundary =
                (release, operation) -> {
                    if (inventoryReceipt.compareAndSet(false, true)) {
                        inventoryWrites.incrementAndGet();
                        return LiveOperationResult.retryable(
                                "crash_after_inventory_receipt",
                                null
                        ).completed();
                    }
                    if (spawnReceipt.compareAndSet(false, true)) {
                        spawnWrites.incrementAndGet();
                    }
                    return LiveOperationResult.confirmed(
                            "capture_release_both_receipts_confirmed"
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
        assertEquals(1, inventoryWrites.get());
        assertEquals(1, spawnWrites.get());
    }

    @Test
    void projectionHoldIsReleasedOnlyAfterCanonicalPublication()
            throws Exception {
        AtomicInteger releases = new AtomicInteger();
        CompanionCaptureReleaseLiveBoundary boundary =
                new CompanionCaptureReleaseLiveBoundary() {
                    @Override
                    public CompletionStage<LiveOperationResult>
                    applyOrResolve(
                            CompanionCaptureReleaseRequest request,
                            OperationEnvelope operation
                    ) {
                        assertEquals(0, releases.get());
                        return LiveOperationResult.confirmed(
                                "capture_release_both_receipts_confirmed"
                        ).completed();
                    }

                    @Override
                    public CompletionStage<Void> releaseProjectionHold(
                            CompanionCaptureReleaseRequest request,
                            OperationEnvelope operation
                    ) {
                        assertEquals(OperationPhase.PUBLISHED, operation.phase());
                        releases.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }
                };

        OperationWorkflowResult result = submit(9, boundary);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(1, releases.get());
    }

    @Test
    void ambiguousInventoryOrSpawnEvidenceQuarantinesExactScopes()
            throws Exception {
        OperationWorkflowResult result = submit(
                3,
                (release, operation) -> LiveOperationResult.unknown(
                        "capture_release_inventory_mutation_ambiguous",
                        null
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                result.status()
        );
        assertEquals(OperationPhase.UNKNOWN, result.operation().phase());
        assertEquals(
                Set.of(
                        OperationScope.operation(operationId(3)),
                        OperationScope.profile(PROFILE),
                        new OperationScope(
                                OperationScopeType.FEATURE,
                                SqliteCompanionCaptureReleaseOperations
                                        .FEATURE_SCOPE
                        )
                ),
                Set.copyOf(result.operation().participants())
        );
        try (Connection connection = connections.openReadConnection()) {
            SqliteIncidentStore incidents = new SqliteIncidentStore(
                    connection
            );
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.operation(operationId(3))
                    ).orElseThrow().state()
            );
            assertEquals(
                    QuarantineState.ACTIVE,
                    incidents.findQuarantine(
                            OperationScope.profile(PROFILE)
                    ).orElseThrow().state()
            );
            assertTrue(
                    incidents.findQuarantine(OperationScope.owner(
                            OwnerId.parse(ACTOR.toString())
                    )).isEmpty()
            );
            assertTrue(
                    incidents.findQuarantine(OperationScope.global())
                            .isEmpty()
            );
        }
        assertEquals(LifecycleState.CAPTURED, lifecycle().state());
        assertNotNull(lifecycle().activeOperationId());
        assertTrue(snapshot().current());
    }

    @Test
    void durableReadbackRequiresTargetLeaseFromThisOperation()
            throws Exception {
        CompanionCaptureReleaseRequest release = request();
        OperationWorkflowResult result = submit(
                4,
                (request, operation) -> LiveOperationResult.confirmed(
                        "capture_release_both_receipts_confirmed"
                ).completed()
        );

        assertTrue(preparationMatches(release, result.operation()));
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE companion_alias
                     SET lease_operation_id = NULL
                     WHERE npc_uuid = ?
                     """)) {
            statement.setString(1, TARGET_ALIAS.toString());
            statement.executeUpdate();
        }

        assertTrue(!preparationMatches(release, result.operation()));
    }

    @Test
    void durableReadbackRequiresExactRetiredSourceSnapshot()
            throws Exception {
        CompanionCaptureReleaseRequest release = request();
        OperationWorkflowResult result = submit(
                5,
                (request, operation) -> LiveOperationResult.confirmed(
                        "capture_release_both_receipts_confirmed"
                ).completed()
        );

        assertTrue(preparationMatches(release, result.operation()));
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE companion_snapshot
                     SET created_at_ms = created_at_ms + 1
                     WHERE snapshot_id = ?
                     """)) {
            statement.setString(1, SNAPSHOT.toString());
            statement.executeUpdate();
        }

        assertTrue(!preparationMatches(release, result.operation()));
    }

    @Test
    void durableReadbackRequiresRetiredSourceAlias()
            throws Exception {
        CompanionCaptureReleaseRequest release = request();
        OperationWorkflowResult result = submit(
                9,
                (request, operation) -> LiveOperationResult.confirmed(
                        "capture_release_both_receipts_confirmed"
                ).completed()
        );

        assertTrue(preparationMatches(release, result.operation()));
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE companion_alias
                     SET alias_state = 'LEASED', retired_at_ms = NULL,
                         lease_operation_id = ?
                     WHERE npc_uuid = ?
                     """)) {
            statement.setString(1, result.operation().operationId().toString());
            statement.setString(2, SOURCE_ALIAS.toString());
            assertEquals(1, statement.executeUpdate());
        }

        assertTrue(!preparationMatches(release, result.operation()));
    }

    @Test
    void durableReadbackRejectsAnotherCurrentCaptureSnapshot()
            throws Exception {
        CompanionCaptureReleaseRequest release = request();
        OperationWorkflowResult result = submit(
                10,
                (request, operation) -> LiveOperationResult.confirmed(
                        "capture_release_both_receipts_confirmed"
                ).completed()
        );
        assertTrue(preparationMatches(release, result.operation()));
        String payload = "{\"capture\":\"tampered-current\"}";
        LifecycleRevision activeRevision = lifecycle().revision();
        try (Connection connection = connections.openWriterConnection()) {
            assertTrue(new SqliteCompanionSnapshotStore(connection)
                    .replaceCurrent(
                    new CompanionSnapshot(
                            OTHER_SNAPSHOT,
                            PROFILE,
                            CompanionCaptureRequest.SNAPSHOT_KIND,
                            1,
                            payload,
                            Sha256Hash.ofUtf8(payload),
                            activeRevision,
                            true,
                            -100
                    )
            ).applied());
        }

        assertTrue(!preparationMatches(release, result.operation()));
    }

    @Test
    void commitRejectsSnapshotThatStoppedBeingTheExactCurrentSource()
            throws Exception {
        OperationWorkflowResult result = submit(
                6,
                (request, operation) -> {
                    try (Connection connection =
                                 connections.openWriterConnection();
                         PreparedStatement statement =
                                 connection.prepareStatement("""
                                         UPDATE companion_snapshot
                                         SET created_at_ms = created_at_ms + 1
                                         WHERE snapshot_id = ?
                                         """)) {
                        statement.setString(1, SNAPSHOT.toString());
                        statement.executeUpdate();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    return LiveOperationResult.confirmed(
                            "capture_release_both_receipts_confirmed"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                result.status()
        );
        assertEquals(LifecycleState.CAPTURED, lifecycle().state());
        assertNotNull(lifecycle().activeOperationId());
        assertEquals(
                CompanionAlias.State.LEASED,
                alias(TARGET_ALIAS).state()
        );
        assertTrue(snapshot().current());
    }

    @Test
    void importedCaptureSnapshotAtLifecycleRevisionReleasesEndToEnd()
            throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE companion_lifecycle
                     SET revision = 0
                     WHERE profile_id = ?
                     """)) {
            statement.setString(1, PROFILE.toString());
            assertEquals(1, statement.executeUpdate());
        }
        CompanionCaptureReleaseRequest imported = request(
                LifecycleRevision.INITIAL
        );

        OperationWorkflowResult result = submit(
                7,
                imported,
                (request, operation) -> LiveOperationResult.confirmed(
                        "capture_release_both_receipts_confirmed"
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status(),
                String.valueOf(result.failure())
        );
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        assertEquals(new LifecycleRevision(2), lifecycle().revision());
        assertEquals(CompanionAlias.State.CURRENT, alias(TARGET_ALIAS).state());
        assertTrue(!snapshot().current());
    }

    @Test
    void alreadyMigratedUnloadedHistoryReleasesOnceWithoutLegacyDatabase()
            throws Exception {
        seedAlreadyMigratedStrandedShape();
        CompanionCaptureReleaseRequest recovery = legacyRecoveryRequest();
        AtomicInteger liveCalls = new AtomicInteger();
        CompanionCaptureReleaseLiveBoundary boundary = (request, operation) -> {
            liveCalls.incrementAndGet();
            assertEquals(LifecycleState.UNLOADED, lifecycle().state());
            assertTrue(!snapshot().current());
            return LiveOperationResult.confirmed(
                    "capture_release_both_receipts_confirmed"
            ).completed();
        };

        OperationWorkflowResult first = submit(13, recovery, boundary);
        OperationWorkflowResult replay = submit(13, recovery, boundary);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, first.status());
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(1, liveCalls.get());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        assertEquals(
                LifecycleLocation.liveEntity(
                        TARGET_ALIAS.toString(),
                        "world-two"
                ),
                lifecycle().location()
        );
        assertEquals(CompanionAlias.State.CURRENT, alias(TARGET_ALIAS).state());
        assertEquals(CompanionAlias.State.RETIRED, alias(SOURCE_ALIAS).state());
        assertTrue(!snapshot().current());
    }

    @Test
    void newerSameProfileCaptureItemSupersedesOlderCapturedAuthority()
            throws Exception {
        CompanionCaptureReleaseRequest recovery = modernRecoveryRequest();
        AtomicInteger liveCalls = new AtomicInteger();
        CompanionCaptureReleaseLiveBoundary boundary = (request, operation) -> {
            liveCalls.incrementAndGet();
            assertEquals(LifecycleState.CAPTURED, lifecycle().state());
            assertEquals(CompanionAlias.State.RETIRED,
                    alias(NEWER_SOURCE_ALIAS).state());
            return LiveOperationResult.confirmed(
                    "capture_release_both_receipts_confirmed"
            ).completed();
        };

        OperationWorkflowResult first = submit(14, recovery, boundary);
        OperationWorkflowResult replay = submit(14, recovery, boundary);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, first.status());
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(1, liveCalls.get());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        assertEquals(CompanionAlias.State.CURRENT, alias(TARGET_ALIAS).state());
        assertEquals(CompanionAlias.State.RETIRED, alias(SOURCE_ALIAS).state());
        assertEquals(CompanionAlias.State.RETIRED,
                alias(NEWER_SOURCE_ALIAS).state());
        assertTrue(!snapshot().current());
    }

    @Test
    void ownerAssignmentAppliesOnlyToAnUnownedCapturedProfile()
            throws Exception {
        setCapturedOwner(null);

        OperationWorkflowResult result = submit(
                11,
                request(new LifecycleRevision(1), ASSIGNED_OWNER),
                (request, operation) -> LiveOperationResult.confirmed(
                        "capture_release_both_receipts_confirmed"
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status(),
                String.valueOf(result.failure())
        );
        assertEquals(ASSIGNED_OWNER, lifecycle().ownerId());
        assertTrue(result.operation().participants().contains(
                OperationScope.owner(ASSIGNED_OWNER)
        ));
    }

    @Test
    void ownerAssignmentCannotReplaceAnExistingCapturedOwner()
            throws Exception {
        AtomicBoolean liveCalled = new AtomicBoolean();

        OperationWorkflowResult result = submit(
                12,
                request(new LifecycleRevision(1), ASSIGNED_OWNER),
                (request, operation) -> {
                    liveCalled.set(true);
                    return LiveOperationResult.confirmed(
                            "capture_release_both_receipts_confirmed"
                    ).completed();
                }
        );

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertTrue(!liveCalled.get());
        assertEquals(OWNER, lifecycle().ownerId());
        assertEquals(LifecycleState.CAPTURED, lifecycle().state());
    }

    @Test
    void offlineStartupDefersReleaseAndSameRequestLaterCompletes()
            throws Exception {
        OperationWorkflowResult initial = submit(
                8,
                (request, operation) -> LiveOperationResult.retryable(
                        "capture_release_actor_unavailable",
                        null
                ).completed()
        );
        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                initial.status()
        );
        writer.shutdown(Duration.ofSeconds(5));
        reads.shutdown(Duration.ofSeconds(5));
        writer = null;
        reads = null;

        recoveryKernel = new SqlitePersistenceKernel(connections);
        SqlitePublicPersistenceAdapter adapter =
                new SqlitePublicPersistenceAdapter(
                        PublicPersistenceFeatureRegistry.create(),
                        recoveryKernel,
                        PersistenceOperationAdmissionGate.allowAll(),
                        () -> -300,
                        (claim, operation) -> LiveOperationResult.confirmed(
                                "test_refund"
                        ).completed(),
                        event -> {
                        }
                );
        PublicPersistenceLiveBoundaries unavailableActor = boundaries(
                (request, operation) -> LiveOperationResult.retryable(
                        "capture_release_actor_unavailable",
                        null
                ).completed()
        );

        SqlitePublicRecoveryResult recovered = adapter.recover(
                unavailableActor,
                "startup-worker"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status()
        );
        assertEquals(0, recovered.completedCount());
        assertEquals(1, recovered.deferredCount());
        assertEquals(List.of(), recovered.quarantinedScopes());
        try (Connection connection = connections.openReadConnection()) {
            var deferred = new SqliteOperationStore(connection)
                    .find(operationId(8))
                    .orElseThrow();
            assertEquals(OperationPhase.RETRYABLE, deferred.phase());
            assertNull(deferred.leaseOwner());
        }

        OperationWorkflowResult resumed =
                adapter.captureReleaseOperations().submit(
                        operationId(8),
                        new IdempotencyKey("capture-release-8"),
                        request(),
                        (request, operation) ->
                                LiveOperationResult.confirmed(
                                        "capture_release_both_receipts_confirmed"
                                ).completed()
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                resumed.status(),
                String.valueOf(resumed.failure())
        );
        assertNull(resumed.operation().leaseOwner());
    }

    private PublicPersistenceLiveBoundaries boundaries(
            CompanionCaptureReleaseLiveBoundary capturedReleases
    ) {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) ->
                        LiveOperationResult.confirmed("capture").completed(),
                capturedReleases,
                (request, operation) ->
                        LiveOperationResult.confirmed("restoration").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop_capture").completed(),
                (request, operation) ->
                        LiveOperationResult.confirmed("coop_release").completed()
        );
    }

    private OperationWorkflowResult submit(
            int number,
            CompanionCaptureReleaseLiveBoundary boundary
    ) throws Exception {
        return submit(number, request(), boundary);
    }

    private OperationWorkflowResult submit(
            int number,
            CompanionCaptureReleaseRequest request,
            CompanionCaptureReleaseLiveBoundary boundary
    ) throws Exception {
        SqliteCompanionCaptureReleaseOperations.Submission submission =
                releases.submit(
                        operationId(number),
                        new IdempotencyKey("capture-release-" + number),
                        request,
                        boundary
                );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private CompanionCaptureReleaseRequest request() {
        return request(new LifecycleRevision(1));
    }

    private CompanionCaptureReleaseRequest request(
            LifecycleRevision expectedRevision
    ) {
        return request(expectedRevision, null);
    }

    private CompanionCaptureReleaseRequest legacyRecoveryRequest() {
        CompanionSnapshot historical = snapshotValue(false);
        CompanionCaptureReleaseRequest ordinary = request(
                new LifecycleRevision(1)
        );
        return new CompanionCaptureReleaseRequest(
                ordinary.profileId(),
                ordinary.expectedLifecycleRevision(),
                snapshotValue(true),
                ordinary.sourceAlias(),
                ordinary.projection(),
                new CaptureReleaseSourceEvidence(
                        ACTOR,
                        "world-two",
                        2,
                        legacySourceArtifact(),
                        receiptArtifact()
                ),
                ordinary.targetAlias(),
                ordinary.ownerAssignment(),
                ordinary.placement(),
                ordinary.inventoryReceiptKey(),
                ordinary.spawnReceiptKey(),
                ordinary.requestedAtMs(),
                new CaptureReleaseLegacyRecoveryEvidence(
                        historical,
                        new ReconciliationGeneration(1),
                        0,
                        -9_000
                )
        );
    }

    private CompanionCaptureReleaseRequest modernRecoveryRequest() {
        CompanionSnapshot canonical = snapshotValue(true);
        CompanionSnapshot itemSource = modernSnapshot(
                OTHER_SNAPSHOT,
                true
        );
        String projection = "{\"state\":\"newer-item\"}";
        return new CompanionCaptureReleaseRequest(
                PROFILE,
                new LifecycleRevision(1),
                itemSource,
                NEWER_SOURCE_ALIAS,
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        projection,
                        Sha256Hash.ofUtf8(projection)
                ),
                new CaptureReleaseSourceEvidence(
                        ACTOR,
                        "world-two",
                        2,
                        modernSourceArtifact(),
                        receiptArtifact()
                ),
                TARGET_ALIAS,
                null,
                new CompanionSpawnPlacement(
                        "world-two", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "inventory-receipt",
                "spawn-receipt",
                -600,
                null,
                new CaptureReleaseModernRecoveryEvidence(
                        canonical,
                        SOURCE_ALIAS,
                        ReconciliationGeneration.INITIAL,
                        0,
                        -10_000
                )
        );
    }

    private CompanionSnapshot modernSnapshot(
            SnapshotId snapshotId,
            boolean current
    ) {
        String payload = "{\"capture\":\"replacement\"}";
        return new CompanionSnapshot(
                snapshotId,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                CompanionCaptureRequest.SNAPSHOT_VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                new LifecycleRevision(1),
                current,
                -9_500
        );
    }

    private void seedAlreadyMigratedStrandedShape() throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement lifecycle = connection.prepareStatement("""
                     UPDATE companion_lifecycle
                     SET lifecycle_state = 'UNLOADED',
                         location_kind = 'NONE', location_key = NULL,
                         world_key = NULL, owner_world_key = NULL,
                         last_reconciled_generation = 1
                     WHERE profile_id = ?
                     """);
             PreparedStatement snapshot = connection.prepareStatement("""
                     UPDATE companion_snapshot
                     SET is_current = 0
                     WHERE snapshot_id = ?
                     """)) {
            lifecycle.setString(1, PROFILE.toString());
            snapshot.setString(1, SNAPSHOT.toString());
            assertEquals(1, lifecycle.executeUpdate());
            assertEquals(1, snapshot.executeUpdate());
        }
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement alias = connection.prepareStatement("""
                     UPDATE companion_alias
                     SET mapped_at_ms = -9000
                     WHERE npc_uuid = ?
                     """)) {
            alias.setString(1, SOURCE_ALIAS.toString());
            assertEquals(1, alias.executeUpdate());
        }
    }

    private CompanionCaptureReleaseRequest request(
            LifecycleRevision expectedRevision,
            OwnerId ownerAssignment
    ) {
        String projection = "{\"state\":\"frozen\"}";
        return new CompanionCaptureReleaseRequest(
                PROFILE,
                expectedRevision,
                snapshotValue(true),
                SOURCE_ALIAS,
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        projection,
                        Sha256Hash.ofUtf8(projection)
                ),
                new CaptureReleaseSourceEvidence(
                        ACTOR,
                        "world-two",
                        2,
                        sourceArtifact(),
                        receiptArtifact()
                ),
                TARGET_ALIAS,
                ownerAssignment,
                new CompanionSpawnPlacement(
                        "world-two",
                        -12.5,
                        -63.05,
                        -4.5,
                        -0.25f,
                        -1.5f,
                        -0.5f
                ),
                "inventory-receipt",
                "spawn-receipt",
                -600
        );
    }

    private void setCapturedOwner(OwnerId ownerId) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE companion_lifecycle
                     SET owner_uuid = ?, owner_world_key = ?
                     WHERE profile_id = ?
                     """)) {
            if (ownerId == null) {
                statement.setNull(1, java.sql.Types.VARCHAR);
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(1, ownerId.toString());
                statement.setString(2, "world");
            }
            statement.setString(3, PROFILE.toString());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void seedCapturedProfile() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            transaction.identities().createProfile(new CompanionIdentity(
                    PROFILE,
                    "Captured Companion",
                    "role",
                    null,
                    null,
                    "world",
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE,
                    OWNER,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(
                            SOURCE_ALIAS.toString(),
                            "world"
                    ),
                    LifecycleRevision.INITIAL,
                    null,
                    -10_000,
                    ReconciliationGeneration.INITIAL,
                    null
            ));
            insertCurrentAlias(connection);
            transaction.snapshots().replaceCurrent(snapshotValue(true));
            transaction.lifecycles().transition(new LifecycleTransition(
                    LifecycleRevision.INITIAL,
                    null,
                    new CompanionLifecycle(
                            PROFILE,
                            OWNER,
                            LifecycleState.CAPTURED,
                            LifecycleLocation.keyed(
                                    LifecycleLocationKind.CAPTURE_ITEM,
                                    SNAPSHOT.toString()
                            ),
                            new LifecycleRevision(1),
                            null,
                            -9_500,
                            ReconciliationGeneration.INITIAL,
                            null,
                            "world"
                    )
            ));
            connection.commit();
        }
    }

    private void insertCurrentAlias(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_alias(
                    npc_uuid, profile_id, alias_generation, alias_state,
                    lease_operation_id, mapped_at_ms, retired_at_ms
                ) VALUES (?, ?, 0, 'CURRENT', NULL, ?, NULL)
                """)) {
            statement.setString(1, SOURCE_ALIAS.toString());
            statement.setString(2, PROFILE.toString());
            statement.setLong(3, -10_000);
            statement.executeUpdate();
        }
    }

    private CompanionSnapshot snapshotValue(boolean current) {
        String payload = "{\"capture\":\"envelope\"}";
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                LifecycleRevision.INITIAL,
                current,
                -9_500
        );
    }

    private CapturedArtifact sourceArtifact() {
        return artifact(
                "capture-device-filled",
                "\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                        + "\":\"" + SNAPSHOT + "\","
                        + "\"" + TameworkMetadataKeys.COMPANION_PROFILE_ID
                        + "\":\"" + PROFILE + "\","
                        + "\"" + TameworkMetadataKeys.TARGET_UUID
                        + "\":\"" + SOURCE_ALIAS + "\""
        );
    }

    private CapturedArtifact legacySourceArtifact() {
        return artifact(
                "capture-device-filled",
                "\"" + TameworkMetadataKeys.TARGET_UUID
                        + "\":\"" + SOURCE_ALIAS + "\""
        );
    }

    private CapturedArtifact modernSourceArtifact() {
        return artifact(
                "capture-device-filled",
                "\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                        + "\":\"" + OTHER_SNAPSHOT + "\","
                        + "\"" + TameworkMetadataKeys.COMPANION_PROFILE_ID
                        + "\":\"" + PROFILE + "\","
                        + "\"" + TameworkMetadataKeys.TARGET_UUID
                        + "\":\"" + NEWER_SOURCE_ALIAS + "\""
        );
    }

    private CapturedArtifact receiptArtifact() {
        return artifact(
                "capture-device-empty",
                "\"" + TameworkMetadataKeys.CAPTURE_RELEASE_RECEIPT
                        + "\":\"inventory-receipt\""
        );
    }

    private CapturedArtifact artifact(String itemId, String metadata) {
        return CapturedArtifact.create(
                itemId,
                1,
                0.0D,
                0.0D,
                "{" + metadata + "}"
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
                    .findById(SNAPSHOT)
                    .orElseThrow();
        }
    }

    private boolean preparationMatches(
            CompanionCaptureReleaseRequest release,
            com.alechilles.alecstamework.persistence.operation
                    .OperationEnvelope operation
    ) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionCaptureReleasePreparation(release)
                    .matches(
                            new SqlitePersistenceTransactionContext(connection),
                            operation
                    );
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "60000000-0000-0000-0000-%012d",
                number
        ));
    }
}

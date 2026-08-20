package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseLiveBoundary;
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
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainBucket;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused canonical-authority and single-flight tests for managed release. */
class SqliteCompanionManagedCaptureReleaseAdmissionTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET_ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final OwnerId ASSIGNED_OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionCaptureReleaseOperations releases;
    private int admissionCalls;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV2Manager(connections, () -> -10_000).initialize();
        seedCapturedProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(
                writer, reads
        );
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionCaptureReleaseDefinition.INSTANCE
                )),
                units
        );
        admissionCalls = 0;
        SqliteLifecycleAdmissionBinding binding =
                new SqliteLifecycleAdmissionBinding();
        binding.bind(this::authorizeAdmission);
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
                new SqliteOperationReader(reads),
                binding,
                new SqliteLifecycleAdmissionSourceReader(reads),
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
    void managedReleaseUsesCanonicalRoleOwnerAndDomainRows()
            throws Exception {
        seedCommittedDomainSource();
        CompanionCaptureReleaseRequest release = ownedSourceRequest();
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                19,
                release,
                (request, operation) -> {
                    liveCalls.incrementAndGet();
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
        assertEquals(1, admissionCalls);
        assertEquals(1, liveCalls.get());
        assertEquals(OWNER, lifecycle().ownerId());
        assertEquals("world-two", lifecycle().ownerWorldKey());
        try (Connection connection = connections.openReadConnection()) {
            var domains = new SqlitePersistenceTransactionContext(
                    connection).populationDomains();
            var reservations = domains.findByOperation(operationId(19));
            assertEquals(1, reservations.size());
            assertEquals(
                    "world-two", reservations.getFirst().bucket().ownerWorldKey()
            );
            var source = domains.counts(new PopulationDomainBucket(
                    OWNER, "managed-test-domain",
                    PopulationDomainScope.PER_WORLD, "world"
            ));
            var target = domains.counts(new PopulationDomainBucket(
                    OWNER, "managed-test-domain",
                    PopulationDomainScope.PER_WORLD, "world-two"
            ));
            assertEquals(0, source.committedOwned());
            assertEquals(0, source.committedDeployable());
            assertEquals(1, target.committedOwned());
            assertEquals(1, target.committedDeployable());
        }
    }

    @Test
    void concurrentIdenticalManagedReleaseCallsAdmissionAndLiveOnce()
            throws Exception {
        setCapturedOwner(null);
        CompanionCaptureReleaseRequest release = request(OWNER);
        AtomicInteger liveCalls = new AtomicInteger();
        CountDownLatch liveStarted = new CountDownLatch(1);
        CompletableFuture<LiveOperationResult> live = new CompletableFuture<>();
        CompanionCaptureReleaseLiveBoundary boundary = (request, operation) -> {
            liveCalls.incrementAndGet();
            liveStarted.countDown();
            return live;
        };

        SqliteCompanionCaptureReleaseOperations.Submission first =
                releases.submit(
                        operationId(20),
                        new IdempotencyKey("capture-release-20"),
                        release,
                        boundary
                );
        assertTrue(liveStarted.await(10, TimeUnit.SECONDS));
        SqliteCompanionCaptureReleaseOperations.Submission second =
                releases.submit(
                        operationId(20),
                        new IdempotencyKey("capture-release-20"),
                        release,
                        boundary
                );
        live.complete(LiveOperationResult.confirmed(
                "capture_release_both_receipts_confirmed"
        ));

        OperationWorkflowResult firstResult = first.completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        OperationWorkflowResult secondResult = second.completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                firstResult.status(), String.valueOf(firstResult.failure())
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                secondResult.status(), String.valueOf(secondResult.failure())
        );
        assertEquals(1, admissionCalls);
        assertEquals(1, liveCalls.get());
    }

    @Test
    void concurrentDifferentReleaseDoesNotSharePublishedResult()
            throws Exception {
        CompanionCaptureReleaseRequest firstRequest = request(OWNER);
        CompanionCaptureReleaseRequest differentRequest = withSpawnReceipt(
                firstRequest, "different-spawn-receipt"
        );
        SqliteLifecycleAdmissionSingleFlight flights =
                new SqliteLifecycleAdmissionSingleFlight();
        AtomicInteger workCalls = new AtomicInteger();
        CompletableFuture<String> firstWork = new CompletableFuture<>();

        CompletionStage<String> first = flights.submit(
                CompanionCaptureReleaseDefinition.KIND,
                operationId(21),
                new IdempotencyKey("capture-release-21"),
                CompanionCaptureReleaseDefinition.INSTANCE.encode(firstRequest),
                () -> {
                    workCalls.incrementAndGet();
                    return firstWork;
                }
        );
        CompletionStage<String> different = flights.submit(
                CompanionCaptureReleaseDefinition.KIND,
                operationId(21),
                new IdempotencyKey("capture-release-21"),
                CompanionCaptureReleaseDefinition.INSTANCE.encode(differentRequest),
                () -> {
                    workCalls.incrementAndGet();
                    return CompletableFuture.completedFuture("different");
                }
        );

        assertEquals("different", different.toCompletableFuture()
                .get(10, TimeUnit.SECONDS));
        firstWork.complete("first");
        assertEquals("first", first.toCompletableFuture()
                .get(10, TimeUnit.SECONDS));
        assertEquals(2, workCalls.get());
    }

    @Test
    void staleCanonicalAliasFailsBeforeAdmissionProvider() throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE companion_alias
                     SET alias_state = 'RETIRED', retired_at_ms = -1
                     WHERE npc_uuid = ?
                     """)) {
            statement.setString(1, SOURCE_ALIAS.toString());
            assertEquals(1, statement.executeUpdate());
        }
        AtomicInteger liveCalls = new AtomicInteger();

        OperationWorkflowResult result = submit(
                22,
                ownedSourceRequest(),
                (request, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed("unexpected").completed();
                }
        );

        assertEquals(OperationWorkflowResult.Status.PREPARE_FAILED, result.status());
        assertEquals(0, admissionCalls);
        assertEquals(0, liveCalls.get());
    }

    private CompletionStage<LifecycleAdmissionEvidence> authorizeAdmission(
            LifecycleAdmissionRequest request
    ) {
        admissionCalls++;
        assertEquals("role", request.targetRoleId());
        return CompletableFuture.completedFuture(
                managedEvidence(
                        request.operationId(),
                        request.sourceOwner(),
                        request.sourceWorld()
                )
        );
    }

    private LifecycleAdmissionEvidence managedEvidence(
            OperationId operationId,
            OwnerId sourceOwner,
            String sourceWorld
    ) {
        PopulationDomainAdmissionOperation.Payload payload =
                new PopulationDomainAdmissionOperation.Payload(
                        UUID.nameUUIDFromBytes((operationId.value()
                                + ":lifecycle-admission").getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )),
                        PROFILE,
                        OWNER,
                        new LifecycleRevision(1),
                        "world-two",
                        sourceOwner,
                        sourceWorld,
                        LifecycleState.CAPTURED,
                        LifecycleState.ACTIVE,
                        "managed-test-group",
                        "managed-test-provider",
                        1,
                        "generation",
                        1,
                        1,
                        Long.MAX_VALUE,
                        1,
                        List.of(new PopulationDomainAdmissionOperation.DomainInput(
                                "managed-test-domain",
                                PopulationDomainScope.PER_WORLD,
                                "world-two",
                                1,
                                1,
                                1,
                                100,
                                100,
                                1
                        )),
                        List.of(),
                        -400
                );
        return LifecycleAdmissionEvidence.managed(payload, null);
    }

    private CompanionCaptureReleaseRequest request(OwnerId ownerAssignment) {
        String projection = "{\"state\":\"frozen\"}";
        return new CompanionCaptureReleaseRequest(
                PROFILE,
                new LifecycleRevision(1),
                snapshotValue(true),
                SOURCE_ALIAS,
                new SnapshotCodecRegistry.EncodedSnapshot(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        projection,
                        Sha256Hash.ofUtf8(projection)
                ),
                new com.alechilles.alecstamework.companion.capture
                        .CaptureReleaseSourceEvidence(
                        ACTOR,
                        "world-two",
                        2,
                        sourceArtifact(),
                        receiptArtifact()
                ),
                TARGET_ALIAS,
                ownerAssignment,
                new CompanionSpawnPlacement(
                        "world-two", -12.5, -63.05, -4.5,
                        -0.25f, -1.5f, -0.5f
                ),
                "inventory-receipt",
                "spawn-receipt",
                -600
        );
    }

    private CompanionCaptureReleaseRequest ownedSourceRequest() {
        CompanionCaptureReleaseRequest base = request(null);
        return new CompanionCaptureReleaseRequest(
                base.profileId(),
                base.expectedLifecycleRevision(),
                base.sourceSnapshot(),
                base.sourceAlias(),
                base.projection(),
                new com.alechilles.alecstamework.companion.capture
                        .CaptureReleaseSourceEvidence(
                        base.source().actorUuid(),
                        base.source().worldKey(),
                        base.source().slot(),
                        ownedSourceArtifact(),
                        base.source().receiptArtifact()
                ),
                base.targetAlias(),
                base.ownerAssignment(),
                base.placement(),
                base.inventoryReceiptKey(),
                base.spawnReceiptKey(),
                base.requestedAtMs()
        );
    }

    private CompanionCaptureReleaseRequest withSpawnReceipt(
            CompanionCaptureReleaseRequest base,
            String spawnReceipt
    ) {
        return new CompanionCaptureReleaseRequest(
                base.profileId(),
                base.expectedLifecycleRevision(),
                base.sourceSnapshot(),
                base.sourceAlias(),
                base.projection(),
                base.source(),
                base.targetAlias(),
                base.ownerAssignment(),
                base.placement(),
                base.inventoryReceiptKey(),
                spawnReceipt,
                base.requestedAtMs()
        );
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
                    PROFILE, "Captured Companion", "role", null, null,
                    "world", -10_000, -10_000, -10_000, 0
            ));
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE,
                    OWNER,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(
                            SOURCE_ALIAS.toString(), "world"
                    ),
                    LifecycleRevision.INITIAL,
                    null,
                    -10_000,
                    ReconciliationGeneration.INITIAL,
                    null
            ));
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

    private void seedCommittedDomainSource() throws Exception {
        OperationId sourceOperation = operationId(190);
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO operation_envelope(
                        operation_id, idempotency_key, operation_kind,
                        payload_version, payload_json, phase, feature_scope,
                        expected_lifecycle_revision, lease_owner, lease_until_ms,
                        attempt_count, failure_kind, failure_code, created_at_ms,
                        updated_at_ms, durable_at_ms, published_at_ms, terminal_at_ms
                    ) VALUES (?, ?, 'seed_domain', 1, '{}', 'PUBLISHED',
                              'seed', 0, NULL, 0, 0, NULL, NULL,
                              -500, -500, -500, -500, NULL)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, "seed-domain-190");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO operation_participant(operation_id, scope_type, scope_key)
                    VALUES (?, 'PROFILE', ?), (?, 'OWNER', ?)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, PROFILE.toString());
                statement.setString(3, sourceOperation.toString());
                statement.setString(4, OWNER.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO population_domain_reservation(
                        operation_id, profile_id, expected_lifecycle_revision,
                        owner_uuid, domain_id, scope_kind, owner_world_key,
                        owned_delta, deployable_delta, weight,
                        snapshotted_max_owned, snapshotted_max_deployable,
                        policy_revision, created_at_ms
                    ) VALUES (?, ?, 0, ?, 'managed-test-domain', 'PER_WORLD',
                              'world', 1, 0, 1, 100, 100, 1, -500)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, PROFILE.toString());
                statement.setString(3, OWNER.toString());
                statement.executeUpdate();
            }
            connection.commit();
        }
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

    private CapturedArtifact ownedSourceArtifact() {
        return artifact(
                "capture-device-filled",
                "\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                        + "\":\"" + SNAPSHOT + "\","
                        + "\"" + TameworkMetadataKeys.COMPANION_PROFILE_ID
                        + "\":\"" + PROFILE + "\","
                        + "\"" + TameworkMetadataKeys.TARGET_UUID
                        + "\":\"" + SOURCE_ALIAS + "\","
                        + "\"" + TameworkMetadataKeys.OWNER_UUID
                        + "\":\"" + ASSIGNED_OWNER + "\","
                        + "\"" + TameworkMetadataKeys.CAPTURE_ROLE_ID
                        + "\":\"wrong-item-role\""
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
                itemId, 1, 0.0D, 0.0D, "{" + metadata + "}"
        );
    }

    private CompanionLifecycle lifecycle() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE)
                    .orElseThrow();
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "60000000-0000-0000-0000-%012d", number
        ));
    }
}

package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CaptureSourceEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainBucket;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Managed ordinary-capture admission, convergence, and replay regressions. */
class SqliteCompanionManagedCaptureAdmissionTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final String SNAPSHOT_JSON = "{\"capturedAtMs\":-500}";

    @TempDir
    Path tempDir;
    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionCaptureOperations captures;
    private AtomicInteger admissionCalls;
    private AdmissionMode admissionMode;
    private boolean positiveTarget;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV2Manager(connections, () -> -10_000).initialize();
        seedLiveProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        admissionCalls = new AtomicInteger();
        admissionMode = AdmissionMode.NEUTRAL;
        positiveTarget = false;
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(
                        List.of(CompanionCaptureDefinition.INSTANCE)
                ),
                units
        );
        SqliteLifecycleAdmissionBinding binding = admissionBinding();
        captures = new SqliteCompanionCaptureOperations(
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
                (claim, operation) -> LiveOperationResult.confirmed(
                        "managed_capture_refund_confirmed"
                ).completed(),
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
    void managedOrdinaryCaptureReducesDeployableUsageAndReplaysWithoutProviderOrLive()
            throws Exception {
        admissionMode = AdmissionMode.MANAGED;
        seedCommittedDomainSource();
        CompanionCaptureRequest request = captureRequest();
        AtomicInteger liveCalls = new AtomicInteger();
        OperationWorkflowResult first = submit(
                20,
                request,
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed(
                            "managed_capture_confirmed"
                    ).completed();
                }
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, first.status());
        assertEquals(1, admissionCalls.get());
        assertEquals(1, liveCalls.get());
        CompanionCaptureRequest durable = CompanionCaptureDefinition.INSTANCE.decode(
                first.operation().payloadJson()
        );
        assertEquals(
                LifecycleAdmissionEvidence.Status.MANAGED,
                durable.admissionEvidence().status()
        );
        assertTrue(durable.admissionEvidence().payload().domains().isEmpty());
        assertNotNull(durable.admissionEvidence().convergencePlan());
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            PopulationDomainBucket target = new PopulationDomainBucket(
                    OWNER,
                    "managed-test-domain",
                    PopulationDomainScope.PER_WORLD,
                    "world"
            );
            var counts = transaction.populationDomains().counts(target);
            assertEquals(1, counts.committedOwned());
            assertEquals(0, counts.committedDeployable());
            assertEquals(1, transaction.populationDomains()
                    .findByOperation(operationId(99)).getFirst().ownedDelta());
            assertEquals(0, transaction.populationDomains()
                    .findByOperation(operationId(99)).getFirst().deployableDelta());
        }
        OperationWorkflowResult replay = submit(
                20,
                request,
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        );
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertEquals(1, admissionCalls.get());
        assertEquals(1, liveCalls.get());
    }

    @Test
    void managedCaptureClearingOwnerPublishesAndClearsOwnership()
            throws Exception {
        admissionMode = AdmissionMode.MANAGED;
        seedCommittedDomainSource();

        OperationWorkflowResult result = submit(
                25,
                captureRequest(null),
                (capture, operation) -> LiveOperationResult.confirmed(
                        "managed_owner_clear_capture_confirmed"
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status(),
                () -> String.valueOf(result.failure())
        );
        CompanionLifecycle lifecycle = lifecycle();
        assertEquals(LifecycleState.CAPTURED, lifecycle.state());
        assertNull(lifecycle.ownerId());
        assertNull(lifecycle.ownerWorldKey());
    }

    @Test
    void managedCaptureClearingOwnerPublishesWithoutDomainClaims()
            throws Exception {
        admissionMode = AdmissionMode.MANAGED;

        OperationWorkflowResult result = submit(
                26,
                captureRequest(null),
                (capture, operation) -> LiveOperationResult.confirmed(
                        "managed_owner_clear_without_claims_confirmed"
                ).completed()
        );

        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                result.status(),
                () -> String.valueOf(result.failure())
        );
        CompanionLifecycle lifecycle = lifecycle();
        assertEquals(LifecycleState.CAPTURED, lifecycle.state());
        assertNull(lifecycle.ownerId());
        assertNull(lifecycle.ownerWorldKey());
    }

    @Test
    void concurrentIdenticalFirstCapturesCallProviderAndLiveOnce()
            throws Exception {
        admissionMode = AdmissionMode.MANAGED;
        seedCommittedDomainSource();
        CompanionCaptureRequest request = captureRequest();
        AtomicInteger liveCalls = new AtomicInteger();
        CompletableFuture<LiveOperationResult> live = new CompletableFuture<>();
        CompanionCaptureLiveBoundary boundary = (capture, operation) -> {
            liveCalls.incrementAndGet();
            return live;
        };
        OperationId operationId = operationId(21);
        IdempotencyKey idempotencyKey = new IdempotencyKey("capture-21");
        SqliteCompanionCaptureOperations.Submission first = captures.submit(
                operationId, idempotencyKey, request, boundary
        );
        SqliteCompanionCaptureOperations.Submission second = captures.submit(
                operationId, idempotencyKey, request, boundary
        );
        live.complete(LiveOperationResult.confirmed(
                "concurrent_capture_confirmed"
        ));
        OperationWorkflowResult firstResult = first.completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        OperationWorkflowResult secondResult = second.completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, firstResult.status());
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, secondResult.status());
        assertEquals(1, admissionCalls.get());
        assertEquals(1, liveCalls.get());
    }
    @Test
    void deniedManagedOrdinaryCaptureNeverReachesLiveBoundary() throws Exception {
        admissionMode = AdmissionMode.DENY;
        AtomicInteger liveCalls = new AtomicInteger();
        OperationWorkflowResult result = submit(
                22,
                captureRequest(),
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        );
        assertEquals(OperationWorkflowResult.Status.PREPARE_FAILED, result.status());
        assertEquals(1, admissionCalls.get());
        assertEquals(0, liveCalls.get());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.populationDomains()
                    .profileEvidence(PROFILE, operationId(22)).committed().isEmpty());
        }
    }
    @Test
    void callerSuppliedPlanlessFirstEvidenceFailsClosed() throws Exception {
        admissionMode = AdmissionMode.MANAGED;
        AtomicInteger liveCalls = new AtomicInteger();
        CompanionCaptureRequest request = captureRequest()
                .withAdmissionEvidence(planlessEvidence());
        OperationWorkflowResult result = submit(
                23,
                request,
                (capture, operation) -> {
                    liveCalls.incrementAndGet();
                    return LiveOperationResult.confirmed("must_not_run")
                            .completed();
                }
        );
        assertEquals(OperationWorkflowResult.Status.PREPARE_FAILED, result.status());
        assertEquals(0, admissionCalls.get());
        assertEquals(0, liveCalls.get());
    }
    @Test
    void compensatedManagedCaptureRetiresPreparedTargetReservations()
            throws Exception {
        admissionMode = AdmissionMode.MANAGED;
        positiveTarget = true;
        makeSourceUnowned();
        OperationWorkflowResult result = submit(
                24,
                captureRequest(),
                (capture, operation) -> LiveOperationResult.compensate(
                        "managed_capture_target_unchanged", null
                ).completed()
        );
        assertEquals(
                OperationWorkflowResult.Status.COMPENSATED,
                result.status(),
                () -> String.valueOf(result.failure())
        );
        assertEquals(1, admissionCalls.get());
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.populationDomains()
                    .findByOperation(operationId(24)).isEmpty());
            assertTrue(transaction.population()
                    .findByOperation(operationId(24)).isEmpty());
            assertTrue(transaction.populationGroups()
                    .findReservations(operationId(24)).isEmpty());
        }
    }
    private OperationWorkflowResult submit(
            int number,
            CompanionCaptureRequest request,
            CompanionCaptureLiveBoundary boundary
    ) throws Exception {
        SqliteCompanionCaptureOperations.Submission submission = captures.submit(
                operationId(number),
                new IdempotencyKey("capture-" + number),
                request,
                boundary
        );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }
    private CompanionCaptureRequest captureRequest() {
        return captureRequest(OWNER);
    }

    private CompanionCaptureRequest captureRequest(OwnerId resultingOwner) {
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SnapshotId.parse("50000000-0000-0000-0000-000000000001"),
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                CompanionCaptureRequest.SNAPSHOT_VERSION,
                SNAPSHOT_JSON,
                Sha256Hash.ofUtf8(SNAPSHOT_JSON),
                new LifecycleRevision(1),
                true,
                -500
        );
        return new CompanionCaptureRequest(
                PROFILE,
                LifecycleRevision.INITIAL,
                resultingOwner,
                ALIAS,
                "world",
                snapshot,
                CapturedArtifact.create(
                        "capture-device-filled",
                        1,
                        0.0D,
                        0.0D,
                        "{\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID + "\":\""
                                + snapshot.snapshotId() + "\"}"
                ),
                new CaptureSourceEvidence(
                        UUID.fromString(
                                "40000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        2,
                        "capture-device",
                        1,
                        Sha256Hash.ofUtf8("before"),
                        snapshot.snapshotId().toString()
                ),
                -600
        );
    }
    private void seedLiveProfile() throws Exception {
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
            transaction.lifecycles().create(new CompanionLifecycle(
                    PROFILE,
                    OWNER,
                    LifecycleState.ACTIVE,
                    LifecycleLocation.liveEntity(ALIAS.toString(), "world"),
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
                statement.setString(1, ALIAS.toString());
                statement.setString(2, PROFILE.toString());
                statement.setLong(3, -10_000);
                statement.executeUpdate();
            }
            connection.commit();
        }
    }
    private void seedCommittedDomainSource() throws Exception {
        OperationId sourceOperation = operationId(99);
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
                statement.setString(2, "seed-domain-99");
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
                              'world', 1, 1, 1, 100, 100, 1, -500)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, PROFILE.toString());
                statement.setString(3, OWNER.toString());
                statement.executeUpdate();
            }
            connection.commit();
        }
    }
    private SqliteLifecycleAdmissionBinding admissionBinding() {
        SqliteLifecycleAdmissionBinding binding =
                new SqliteLifecycleAdmissionBinding();
        binding.bind(this::authorizeAdmission);
        return binding;
    }
    private CompletionStage<LifecycleAdmissionEvidence> authorizeAdmission(
            LifecycleAdmissionRequest request
    ) {
        admissionCalls.incrementAndGet();
        return switch (admissionMode) {
            case DENY -> CompletableFuture.failedFuture(
                    new IllegalStateException("managed-admission-denied")
            );
            case NEUTRAL -> CompletableFuture.completedFuture(
                    LifecycleAdmissionEvidence.neutral()
            );
            case MANAGED -> CompletableFuture.completedFuture(
                    LifecycleAdmissionEvidence.managed(
                            managedPayload(request), null
                    )
            );
        };
    }
    private PopulationDomainAdmissionOperation.Payload managedPayload(
            LifecycleAdmissionRequest request
    ) {
        UUID targetOwnerUuid = request.managedRequest().request().newOwnerUuid();
        OwnerId targetOwner = targetOwnerUuid == null
                ? null : new OwnerId(targetOwnerUuid);
        return new PopulationDomainAdmissionOperation.Payload(
                request.reservationId(),
                PROFILE,
                targetOwner,
                request.source().revision(),
                targetOwner == null ? null : "world",
                request.sourceOwner(),
                request.sourceWorld(),
                request.sourceState(),
                LifecycleState.CAPTURED,
                "managed-test-group",
                "managed-test-provider",
                1,
                "generation",
                1,
                1,
                Long.MAX_VALUE,
                1,
                positiveTarget ? List.of(
                        new PopulationDomainAdmissionOperation.DomainInput(
                                "managed-test-domain",
                                PopulationDomainScope.PER_WORLD,
                                "world",
                                1,
                                0,
                                1,
                                100,
                                100,
                                1
                        )
                ) : List.of(),
                List.of(),
                -400
        );
    }
    private void makeSourceUnowned() throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE companion_lifecycle
                     SET owner_uuid = NULL, owner_world_key = NULL
                     WHERE profile_id = ?
                     """)) {
            statement.setString(1, PROFILE.toString());
            assertEquals(1, statement.executeUpdate());
        }
    }
    private LifecycleAdmissionEvidence planlessEvidence() {
        return LifecycleAdmissionEvidence.managed(
                new PopulationDomainAdmissionOperation.Payload(
                        UUID.fromString(
                                "90000000-0000-0000-0000-000000000023"
                        ),
                        PROFILE,
                        OWNER,
                        LifecycleRevision.INITIAL,
                        "world",
                        OWNER,
                        "world",
                        LifecycleState.ACTIVE,
                        LifecycleState.CAPTURED,
                        "managed-test-group",
                        "managed-test-provider",
                        1,
                        "generation",
                        1,
                        1,
                        Long.MAX_VALUE,
                        1,
                        List.of(),
                        List.of(),
                        -400
                ),
                null
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
                "60000000-0000-0000-0000-%012d",
                number
        ));
    }
    private enum AdmissionMode {
        NEUTRAL,
        MANAGED,
        DENY
    }
}

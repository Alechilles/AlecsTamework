package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionEventCodec;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionOutcome;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
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
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionLifecycleEventCodec;
import com.alechilles.alecstamework.companion.provisioning.ProvisionedCompanionLifecyclePublishedEventMapper;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainBucket;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end shared death/lost snapshot and lifecycle transition tests. */
class SqliteCompanionDormantOperationsTest {
    private static final ProvisioningOrigin PROVISIONING =
            new ProvisioningOrigin("hydragon", "soul-bond:owner");
    private static final ProfileId PROFILE =
            PROVISIONING.profileId();
    private static final NpcAlias ALIAS =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final String SNAPSHOT_JSON = "{\"health\":0}";

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteCompanionDormantOperations dormant;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV2Manager(connections, () -> -10_000).initialize();
        seedLiveProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(writer, reads);
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CompanionDormantTransitionDefinition.INSTANCE
                )),
                units
        );
        dormant = new SqliteCompanionDormantOperations(
                engine,
                new SqliteOperationEvidenceReader(reads),
                new ProjectionCoordinator(
                        new SqliteProjectionGateway(reads, units),
                        ProjectionRetryPolicy.DEFAULT,
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
    void deathSnapshotAliasRetirementAndLifecycleCommitAtomically()
            throws Exception {
        CompanionDormantTransitionRequest request =
                request(DormantSourceEvidence.Kind.DEATH_COMPONENT, 3);

        OperationWorkflowResult result = submit(1, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(3, result.events().size());
        CompanionDormantTransitionOutcome outcome =
                CompanionDormantTransitionEventCodec.decode(
                        result.events().getFirst().payloadVersion(),
                        result.events().getFirst().payloadJson()
                );
        assertEquals(LifecycleState.DEAD_REVIVABLE, outcome.state());
        assertEquals(request.snapshot().snapshotId(), outcome.snapshotId());
        assertDormant(request, LifecycleState.DEAD_REVIVABLE, 3);
    }

    /** Protects the 2026-08-26 regression where dead livestock stayed deployed. */
    @Test
    void deathRetainsOwnedCapacityAndReleasesDeployableCapacity()
            throws Exception {
        seedManagedDomainClaims();
        CompanionDormantTransitionRequest request =
                request(DormantSourceEvidence.Kind.DEATH_COMPONENT, 3);

        OperationWorkflowResult result = submit(11, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        try (Connection connection = connections.openReadConnection()) {
            SqlitePopulationDomainStore domains =
                    new SqlitePopulationDomainStore(connection);
            assertEquals(1, domains.counts(domain("husbandry-owned"))
                    .committedOwned());
            assertEquals(0, domains.counts(domain("husbandry-deployed"))
                    .committedDeployable());
        }
    }

    @Test
    void lostKindsUseSameOperationAndPublishedReplayIsIdempotent()
            throws Exception {
        CompanionDormantTransitionRequest request =
                request(DormantSourceEvidence.Kind.WORLD_DELETION, 4);

        OperationWorkflowResult first = submit(2, request);
        OperationWorkflowResult replay = submit(2, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, first.status());
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, replay.status());
        assertDormant(request, LifecycleState.LOST, 4);
        assertEquals(first.events(), replay.events());
    }

    @Test
    void worldDeletionCommitsLostForExactAliasDespiteStaleWorldHint()
            throws Exception {
        CompanionDormantTransitionRequest request = request(
                DormantSourceEvidence.Kind.WORLD_DELETION,
                4,
                "deleted-instance"
        );

        OperationWorkflowResult result = submit(4, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertDormant(request, LifecycleState.LOST, 4);
    }

    @Test
    void explicitRecallExhaustionMakesExactUnloadedProfileRestorable()
            throws Exception {
        transitionToUnloaded();
        CompanionDormantTransitionRequest request = request(
                DormantSourceEvidence.Kind.EXPLICIT_RECALL_EXHAUSTED,
                3
        );

        OperationWorkflowResult result = submit(6, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertDormant(request, LifecycleState.LOST, 3, 2);
    }

    @Test
    void explicitRecallRejectsMetadataChangedAfterSnapshotRead()
            throws Exception {
        transitionToUnloaded();
        CompanionDormantTransitionRequest request = request(
                DormantSourceEvidence.Kind.EXPLICIT_RECALL_EXHAUSTED,
                3
        );
        updateMetadata("""
                {"custom_name":"Renamed","tamed":false}
                """.trim());

        OperationWorkflowResult result = submit(8, request);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(LifecycleState.UNLOADED, lifecycle().state());
        assertEquals(CompanionAlias.State.CURRENT, alias().state());
        assertTrue(snapshot(request).isEmpty());
    }

    @Test
    void destructiveRemovalCommitsFullStateWhenLoadPrecedesReconciliation()
            throws Exception {
        transitionToUnloaded();
        CompanionDormantTransitionRequest request = unloadedRequest(
                DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL
        );

        OperationWorkflowResult result = submit(7, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertDormant(request, LifecycleState.LOST, 3, 2);
    }

    @Test
    void staleReconciliationEvidenceFailsBeforeOperationPersists()
            throws Exception {
        CompanionDormantTransitionRequest request =
                request(DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL, 1);

        OperationWorkflowResult result = submit(3, request);

        assertEquals(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                result.status()
        );
        assertEquals(LifecycleState.ACTIVE, lifecycle().state());
        assertEquals(CompanionAlias.State.CURRENT, alias().state());
        assertTrue(snapshot(request).isEmpty());
        try (Connection connection = connections.openReadConnection()) {
            assertTrue(new SqliteOperationStore(connection)
                    .find(operationId(3)).isEmpty());
        }
    }

    @Test
    void provisionedDeathPublishesSelfContainedSemanticEvent()
            throws Exception {
        seedProvisioning();
        CompanionDormantTransitionRequest request =
                request(DormantSourceEvidence.Kind.DEATH_COMPONENT, 3);

        OperationWorkflowResult result = submit(5, request);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(4, result.events().size());
        var semantic = result.events().stream()
                .filter(event -> ProvisionedCompanionLifecycleEventCodec
                        .DEATH_EVENT_TYPE.equals(event.eventType()))
                .findFirst()
                .orElseThrow();
        var mapped =
                ProvisionedCompanionLifecyclePublishedEventMapper.mapDeath(
                        semantic, false, -390
                );
        assertEquals(PROVISIONING.callerNamespace(), mapped.callerNamespace());
        assertEquals(PROVISIONING.callerKey(), mapped.provisioningKey());
        assertEquals(PROFILE.toString(), mapped.profileId());
        assertEquals(OWNER.value(), mapped.ownerUuid());
        assertEquals(ALIAS.value(), mapped.lastNpcUuid());
        assertEquals(0, mapped.oldProfileRevision());
        assertEquals(1, mapped.newProfileRevision());
        assertEquals(-500, mapped.diedAtMs());
    }

    private OperationWorkflowResult submit(
            int number,
            CompanionDormantTransitionRequest request
    ) throws Exception {
        SqliteCompanionDormantOperations.Submission submission =
                dormant.submit(
                        operationId(number),
                        new IdempotencyKey("dormant-" + number),
                        request
                );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private void assertDormant(
            CompanionDormantTransitionRequest request,
            LifecycleState state,
            long generation
    ) throws Exception {
        assertDormant(request, state, generation, 1);
    }

    private void assertDormant(
            CompanionDormantTransitionRequest request,
            LifecycleState state,
            long generation,
            long revision
    ) throws Exception {
        CompanionLifecycle lifecycle = lifecycle();
        assertEquals(state, lifecycle.state());
        assertEquals(LifecycleLocation.none(), lifecycle.location());
        assertEquals(new LifecycleRevision(revision), lifecycle.revision());
        assertEquals(OWNER, lifecycle.ownerId());
        assertNull(lifecycle.activeOperationId());
        assertEquals(
                new ReconciliationGeneration(generation),
                lifecycle.lastReconciledGeneration()
        );
        assertEquals(CompanionAlias.State.RETIRED, alias().state());
        assertEquals(request.snapshot(), snapshot(request).orElseThrow());
    }

    private CompanionDormantTransitionRequest request(
            DormantSourceEvidence.Kind kind,
            long generation
    ) {
        return request(kind, generation, "world");
    }

    private CompanionDormantTransitionRequest request(
            DormantSourceEvidence.Kind kind,
            long generation,
            String worldKey
    ) {
        LifecycleRevision revision = kind
                == DormantSourceEvidence.Kind.EXPLICIT_RECALL_EXHAUSTED
                ? new LifecycleRevision(1)
                : LifecycleRevision.INITIAL;
        String payload = kind
                == DormantSourceEvidence.Kind.EXPLICIT_RECALL_EXHAUSTED
                ? explicitRecallSnapshotJson()
                : SNAPSHOT_JSON;
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SnapshotId.parse("50000000-0000-0000-0000-000000000001"),
                PROFILE,
                kind.snapshotKind(),
                kind == DormantSourceEvidence.Kind.EXPLICIT_RECALL_EXHAUSTED
                        ? 2 : 1,
                payload,
                Sha256Hash.ofUtf8(payload),
                revision,
                true,
                -500
        );
        return new CompanionDormantTransitionRequest(
                PROFILE,
                revision,
                snapshot,
                new DormantSourceEvidence(
                        ALIAS,
                        worldKey,
                        kind,
                        new ReconciliationGeneration(generation),
                        "dormant-receipt-" + kind.name().toLowerCase(),
                        -500
                ),
                -600
        );
    }

    private String explicitRecallSnapshotJson() {
        return """
                {"version":"1","npcUuid":"%s","residentSlot":-1,
                "roleId":"role","capturedAtMs":-500,
                "commandLinks":{"ownerId":"%s","toolIds":[]},
                "owner":{"ownerId":"%s"},"tamed":{"tamed":true}}
                """.formatted(ALIAS, OWNER, OWNER)
                .replace("\n", "")
                .trim();
    }

    private CompanionDormantTransitionRequest unloadedRequest(
            DormantSourceEvidence.Kind kind
    ) {
        LifecycleRevision revision = new LifecycleRevision(1);
        String payload = explicitRecallSnapshotJson();
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SnapshotId.parse("50000000-0000-0000-0000-000000000002"),
                PROFILE,
                kind.snapshotKind(),
                2,
                payload,
                Sha256Hash.ofUtf8(payload),
                revision,
                true,
                -500
        );
        return new CompanionDormantTransitionRequest(
                PROFILE,
                revision,
                snapshot,
                new DormantSourceEvidence(
                        ALIAS,
                        "world",
                        kind,
                        new ReconciliationGeneration(3),
                        "dormant-receipt-raced-removal",
                        -500
                ),
                -600
        );
    }

    private void transitionToUnloaded() throws Exception {
        var submitted = writer.submit(new SqliteTransactionCommand<>(
                operationId(95),
                new OperationKind("test_seed_unloaded"),
                TransactionReplayPolicy.NEVER,
                connection -> {
                    SqliteCompanionLifecycleStore lifecycles =
                            new SqliteCompanionLifecycleStore(connection);
                    CompanionLifecycle current = lifecycles.findByProfile(PROFILE)
                            .orElseThrow();
                    lifecycles.transition(new com.alechilles.alecstamework
                            .companion.lifecycle.LifecycleTransition(
                            current.revision(),
                            null,
                            new CompanionLifecycle(
                                    PROFILE,
                                    OWNER,
                                    LifecycleState.UNLOADED,
                                    LifecycleLocation.none(),
                                    current.revision().next(),
                                    null,
                                    -700,
                                    new ReconciliationGeneration(3),
                                    null,
                                    "world"
                            )
                    ));
                    return null;
                }
        ));
        assertTrue(submitted.completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS)
                instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
    }

    private void updateMetadata(String metadata) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE companion_profile
                     SET metadata_json = ?, metadata_hash = ?,
                         metadata_revision = metadata_revision + 1
                     WHERE profile_id = ?
                     """)) {
            statement.setString(1, metadata);
            statement.setString(2, Sha256Hash.ofUtf8(metadata).toString());
            statement.setString(3, PROFILE.toString());
            assertEquals(1, statement.executeUpdate());
        }
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
                    new ReconciliationGeneration(2),
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

    private void seedProvisioning() throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            OperationId creation = operationId(90);
            transaction.operations().prepare(new PreparedOperation(
                    creation,
                    PROVISIONING.operationKey(),
                    CompanionProvisioningDefinition.KIND,
                    1,
                    "{}",
                    SqliteCompanionProvisioningOperations.FEATURE_SCOPE,
                    null,
                    List.of(OperationScope.profile(PROFILE)),
                    -9_000
            ));
            transaction.provisioning().create(new ProvisioningRecord(
                    PROFILE,
                    PROVISIONING,
                    null,
                    1,
                    creation,
                    -9_000
            ));
            connection.commit();
        }
    }

    private void seedManagedDomainClaims() throws Exception {
        OperationId sourceOperation = operationId(191);
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
                statement.setString(2, "seed-domain-191");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO population_domain_reservation(
                        operation_id, profile_id, expected_lifecycle_revision,
                        owner_uuid, domain_id, scope_kind, owner_world_key,
                        owned_delta, deployable_delta, weight,
                        snapshotted_max_owned, snapshotted_max_deployable,
                        policy_revision, created_at_ms
                    ) VALUES (?, ?, 0, ?, ?, 'GLOBAL', '', ?, ?, 1, 12, 6, 1, -500)
                    """)) {
                statement.setString(1, sourceOperation.toString());
                statement.setString(2, PROFILE.toString());
                statement.setString(3, OWNER.toString());
                statement.setString(4, "husbandry-owned");
                statement.setInt(5, 1);
                statement.setInt(6, 0);
                statement.addBatch();
                statement.setString(4, "husbandry-deployed");
                statement.setInt(5, 0);
                statement.setInt(6, 1);
                statement.addBatch();
                statement.executeBatch();
            }
            connection.commit();
        }
    }

    private PopulationDomainBucket domain(String id) {
        return new PopulationDomainBucket(
                OWNER, id, PopulationDomainScope.GLOBAL, null
        );
    }

    private CompanionLifecycle lifecycle() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE)
                    .orElseThrow();
        }
    }

    private CompanionAlias alias() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(ALIAS)
                    .orElseThrow();
        }
    }

    private java.util.Optional<CompanionSnapshot> snapshot(
            CompanionDormantTransitionRequest request
    ) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findCurrent(PROFILE, request.snapshot().kind());
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(String.format(
                "60000000-0000-0000-0000-%012d",
                number
        ));
    }
}

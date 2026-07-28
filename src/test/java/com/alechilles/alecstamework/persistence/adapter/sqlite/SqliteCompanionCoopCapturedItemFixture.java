package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
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
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic SQLite fixture for canonical captured-item to coop transitions.
 *
 * <p>The seeded revision sequence mirrors a real capture: the capture snapshot describes the
 * N+1 capture fence and the current CAPTURED lifecycle is N+2.</p>
 */
final class SqliteCompanionCoopCapturedItemFixture implements AutoCloseable {
    static final ProfileId PROFILE =
            ProfileId.parse("61000000-0000-0000-0000-000000000001");
    static final NpcAlias SOURCE_ALIAS =
            NpcAlias.parse("62000000-0000-0000-0000-000000000001");
    static final OwnerId OWNER =
            OwnerId.parse("63000000-0000-0000-0000-000000000001");
    static final UUID ACTOR =
            UUID.fromString("64000000-0000-0000-0000-000000000001");
    static final SnapshotId CAPTURE_SNAPSHOT_ID =
            SnapshotId.parse("65000000-0000-0000-0000-000000000001");
    static final SnapshotId COOP_SNAPSHOT_ID =
            SnapshotId.parse("65000000-0000-0000-0000-000000000002");
    static final LifecycleRevision CAPTURE_SNAPSHOT_REVISION =
            new LifecycleRevision(1);
    static final LifecycleRevision CAPTURED_REVISION =
            new LifecycleRevision(2);
    static final CoopSlotKey SLOT =
            new CoopSlotKey("world", "captured-item-coop", 4, 70, 8, 0);
    static final String RECEIPT = "captured-item-coop-receipt";

    final SqliteConnectionFactory connections;
    final SqliteSingleWriter writer;
    final SqliteReadExecutor reads;
    final SqliteCompanionCoopCaptureOperations captures;

    SqliteCompanionCoopCapturedItemFixture(Path directory) throws Exception {
        Files.createDirectories(directory);
        connections = new SqliteConnectionFactory(
                directory.resolve("captured-item-coop.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        seedCapturedProfile();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(
                writer, reads
        );
        SqliteOperationEngine engine = new SqliteOperationEngine(
                new OperationDefinitionRegistry(List.of(
                        CoopSlotRegistrationDefinition.INSTANCE,
                        CompanionCoopCaptureDefinition.INSTANCE
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
        captures = new SqliteCompanionCoopCaptureOperations(
                engine, publisher, () -> -400, List.of()
        );
        registerSlot(engine, evidence, projections);
    }

    OperationWorkflowResult capture(
            int number,
            CompanionCoopCaptureLiveBoundary boundary
    ) throws Exception {
        return captures.submit(
                operationId(number),
                new IdempotencyKey("captured-item-coop-" + number),
                request(),
                boundary
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    CompanionCoopCaptureRequest request() {
        CompanionSnapshot source = captureSnapshot();
        return new CompanionCoopCaptureRequest(
                PROFILE,
                CAPTURED_REVISION,
                SLOT,
                coopSnapshot(),
                new CoopCapturedItemSourceEvidence(
                        SOURCE_ALIAS,
                        PROFILE,
                        source,
                        ACTOR,
                        SLOT.worldKey(),
                        new CoopCapturedItemInventoryPosition(
                                CoopCapturedItemInventoryPosition.Section.HOTBAR,
                                2
                        ),
                        sourceArtifact(source),
                        receiptArtifact(source),
                        RECEIPT
                ),
                -600
        );
    }

    CompanionLifecycle lifecycle() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionLifecycleStore(connection)
                    .findByProfile(PROFILE).orElseThrow();
        }
    }

    CoopSlot slot() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionCoopStore(connection)
                    .findSlot(SLOT).orElseThrow();
        }
    }

    Optional<CoopResidency> residency() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionCoopStore(connection)
                    .findResidencyBySlot(SLOT);
        }
    }

    CompanionSnapshot snapshot(SnapshotId snapshotId) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findById(snapshotId).orElseThrow();
        }
    }

    Optional<CompanionSnapshot> currentSnapshot(
            SnapshotKind kind
    ) throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionSnapshotStore(connection)
                    .findCurrent(PROFILE, kind);
        }
    }

    CompanionAlias alias() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(SOURCE_ALIAS).orElseThrow();
        }
    }

    void retireCaptureSnapshotDirectly() throws Exception {
        update("""
                UPDATE companion_snapshot
                SET is_current = 0
                WHERE snapshot_id = ?
                """, CAPTURE_SNAPSHOT_ID.toString());
    }

    void makeLifecycleStale() throws Exception {
        update("""
                UPDATE companion_lifecycle
                SET revision = revision + 1
                WHERE profile_id = ?
                """, PROFILE.toString());
    }

    void retireAliasDirectly() throws Exception {
        update("""
                UPDATE companion_alias
                SET alias_state = 'RETIRED', retired_at_ms = -50
                WHERE npc_uuid = ?
                """, SOURCE_ALIAS.toString());
    }

    void occupySlotDirectly() throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO coop_residency(
                         coop_key, profile_id, housed_npc_uuid, snapshot_id,
                         captured_at_ms, updated_at_ms
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, SLOT.toString());
            statement.setString(2, PROFILE.toString());
            statement.setString(3, SOURCE_ALIAS.toString());
            statement.setString(4, CAPTURE_SNAPSHOT_ID.toString());
            statement.setLong(5, -100);
            statement.setLong(6, -100);
            statement.executeUpdate();
        }
    }

    @Override
    public void close() {
        writer.shutdown(Duration.ofSeconds(5));
        reads.shutdown(Duration.ofSeconds(5));
    }

    private void registerSlot(
            SqliteOperationEngine engine,
            SqliteOperationEvidenceReader evidence,
            ProjectionCoordinator projections
    ) throws Exception {
        SqliteCoopSlotOperations slots = new SqliteCoopSlotOperations(
                new SqliteDatabaseOperationCoordinator(
                        engine, evidence, projections, () -> -500
                ),
                List.of()
        );
        OperationWorkflowResult result = slots.submit(
                operationId(90),
                new IdempotencyKey("captured-item-slot"),
                new CoopSlotRegistration(CoopSlot.unoccupied(SLOT), -700)
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (result.status() != OperationWorkflowResult.Status.PUBLISHED) {
            throw new IllegalStateException(
                    "Captured-item fixture slot registration failed"
            );
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
                    "captured_role",
                    null,
                    null,
                    "world",
                    -10_000,
                    -10_000,
                    -10_000,
                    0
            ));
            CompanionLifecycle initial = new CompanionLifecycle(
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
                    null,
                    "world"
            );
            transaction.lifecycles().create(initial);
            insertAlias(connection);
            CompanionLifecycle captureFence = new CompanionLifecycle(
                    PROFILE,
                    OWNER,
                    LifecycleState.ACTIVE,
                    initial.location(),
                    CAPTURE_SNAPSHOT_REVISION,
                    null,
                    -9_500,
                    ReconciliationGeneration.INITIAL,
                    null,
                    "world"
            );
            transaction.lifecycles().transition(new LifecycleTransition(
                    LifecycleRevision.INITIAL, null, captureFence
            ));
            transaction.snapshots().replaceCurrent(captureSnapshot());
            transaction.lifecycles().transition(new LifecycleTransition(
                    CAPTURE_SNAPSHOT_REVISION,
                    null,
                    new CompanionLifecycle(
                            PROFILE,
                            OWNER,
                            LifecycleState.CAPTURED,
                            LifecycleLocation.keyed(
                                    LifecycleLocationKind.CAPTURE_ITEM,
                                    CAPTURE_SNAPSHOT_ID.toString()
                            ),
                            CAPTURED_REVISION,
                            null,
                            -9_000,
                            ReconciliationGeneration.INITIAL,
                            null,
                            "world"
                    )
            ));
            connection.commit();
        }
    }

    private void insertAlias(Connection connection) throws Exception {
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

    private CompanionSnapshot captureSnapshot() {
        String payload = """
                {"npcUuid":"%s","health":77,"coopId":null,"residentSlot":-1}
                """.formatted(SOURCE_ALIAS).trim();
        return snapshot(
                CAPTURE_SNAPSHOT_ID,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                CompanionCaptureRequest.SNAPSHOT_VERSION,
                payload,
                CAPTURE_SNAPSHOT_REVISION
        );
    }

    private CompanionSnapshot coopSnapshot() {
        String payload = """
                {"npcUuid":"%s","health":77,"coopId":"%s","residentSlot":%d}
                """.formatted(
                SOURCE_ALIAS, SLOT.coopId(), SLOT.residentSlot()
        ).trim();
        return snapshot(
                COOP_SNAPSHOT_ID,
                CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                CompanionCoopCaptureRequest.SNAPSHOT_VERSION,
                payload,
                CAPTURED_REVISION.next()
        );
    }

    private CompanionSnapshot snapshot(
            SnapshotId snapshotId,
            SnapshotKind kind,
            int version,
            String payload,
            LifecycleRevision sourceRevision
    ) {
        return new CompanionSnapshot(
                snapshotId,
                PROFILE,
                kind,
                version,
                payload,
                Sha256Hash.ofUtf8(payload),
                sourceRevision,
                true,
                -8_500
        );
    }

    private CapturedArtifact sourceArtifact(CompanionSnapshot source) {
        return artifact("""
                {
                  "%s":"%s",
                  "%s":"%s",
                  "%s":"%s"
                }
                """.formatted(
                TameworkMetadataKeys.TARGET_UUID, SOURCE_ALIAS,
                TameworkMetadataKeys.COMPANION_PROFILE_ID, PROFILE,
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                source.snapshotId()
        ));
    }

    private CapturedArtifact receiptArtifact(CompanionSnapshot source) {
        return artifact("""
                {
                  "%s":"%s",
                  "%s":"%s",
                  "%s":"%s",
                  "%s":"%s"
                }
                """.formatted(
                TameworkMetadataKeys.TARGET_UUID, SOURCE_ALIAS,
                TameworkMetadataKeys.COMPANION_PROFILE_ID, PROFILE,
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                source.snapshotId(),
                CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY,
                RECEIPT
        ));
    }

    private CapturedArtifact artifact(String metadata) {
        return CapturedArtifact.create(
                "AnimalHusbandry_Soul_Lantern_Filled",
                1,
                0.0D,
                0.0D,
                metadata
        );
    }

    private void update(String sql, String value) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private OperationId operationId(int number) {
        return OperationId.parse(
                "66000000-0000-0000-0000-%012d".formatted(number)
        );
    }
}

package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for durable lost envelopes and profile-first recovery reads. */
class LostRepositoryTest {
    private static final UUID SOURCE_A = uuid(1L);
    private static final UUID SOURCE_B = uuid(2L);
    private static final UUID TARGET_A = uuid(101L);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue writeQueue;
    private LostRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("lost-repository.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        writeQueue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
        repository = new LostRepository(connections, writeQueue);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void trackedFullEnvelopeRoundTripPreservesSignedStateAndHash() throws Exception {
        CommandLinkedNpcLostService.LostLinkedNpcSnapshot lost = lost(SOURCE_A, null);
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot full = fullSnapshot(SOURCE_A);

        LostRecoveryWriteResult written = committed(repository.upsertTracked(lost, full));

        assertEquals(SOURCE_A, written.sourceNpcUuid());
        assertEquals(1, written.formatVersion());
        assertTrue(written.fullSnapshotStored());
        assertEquals(64, written.fullSnapshotSha256().length());
        LostRecoveryLoadResult byProfile = repository.loadAwaitingByProfile(written.profileId());
        assertEquals(LostRecoveryLoadResult.Status.FOUND, byProfile.status());
        LostRecoveryEnvelope envelope = byProfile.envelope();
        assertNotNull(envelope);
        assertEquals(SOURCE_A, envelope.sourceNpcUuid());
        assertEquals(SOURCE_A, envelope.currentNpcUuid());
        assertEquals(-201L, envelope.metadata().lastRelocationQueuedAtMs());
        assertEquals(-202L, envelope.metadata().lostAtMs());
        assertEquals(-203L, envelope.metadata().recoveredAtMs());
        assertEquals(-9_001L, envelope.fullSnapshot().capturedAtMs());
        assertEquals(-101L, envelope.fullSnapshot().happiness().getLastUpdateMs());
        assertEquals(written.fullSnapshotSha256(), envelope.fullSnapshotSha256());
        assertEquals(LostRecoveryLoadResult.Status.FOUND,
                repository.loadAwaitingBySourceUuid(SOURCE_A).status());
        execute("UPDATE npc_profiles SET current_npc_uuid = '" + SOURCE_B
                + "' WHERE profile_id = '" + written.profileId() + "'");
        assertEquals(-202L, repository.loadAll().getFirst().lostAtMs());
        assertEquals(SOURCE_A, repository.loadAll().getFirst().npcUuid());
        assertEquals(-202L, activeCreatedAt(written.profileId()));

        String raw = activePayload(written.profileId());
        JsonObject payload = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals(SOURCE_A.toString(), payload.get("sourceNpcUuid").getAsString());
        assertEquals(1, payload.get("recoveryEnvelopeVersion").getAsInt());
        assertTrue(payload.get("fullStateSnapshot").isJsonObject());
    }

    @Test
    void trackedDeleteQueuedAfterAcceptedUpsertLeavesNoActiveLostState() throws Exception {
        PersistenceWriteQueue.WriteSubmission<LostRecoveryWriteResult> upsert =
                repository.upsertTracked(lost(SOURCE_A, null), fullSnapshot(SOURCE_A));
        PersistenceWriteQueue.WriteSubmission<Void> cancellation = repository.deleteTracked(SOURCE_A);

        LostRecoveryWriteResult written = committed(upsert);
        assertTrue(cancellation.accepted());
        PersistenceWriteQueue.WriteOutcome<Void> cancellationOutcome =
                cancellation.completion().get(3, TimeUnit.SECONDS);

        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, cancellationOutcome.status());
        assertEquals(LostRecoveryLoadResult.Status.NOT_FOUND,
                repository.loadAwaitingByProfile(written.profileId()).status());
        assertTrue(repository.loadAll().isEmpty());
    }

    @Test
    void tamperedSnapshotHashFailsClosed() throws Exception {
        LostRecoveryWriteResult written = committed(
                repository.upsertTracked(lost(SOURCE_A, null), fullSnapshot(SOURCE_A)));
        JsonObject payload = activePayloadObject(written.profileId());
        payload.getAsJsonObject("fullStateSnapshot").addProperty("capturedAtMs", -999L);
        updateActivePayload(written.profileId(), payload.toString());

        LostRecoveryLoadResult result = repository.loadAwaitingByProfile(written.profileId());

        assertEquals(LostRecoveryLoadResult.Status.FAILED, result.status());
        assertEquals(LostRecoveryLoadResult.Failure.SNAPSHOT_HASH_INVALID, result.failure());
        assertNull(result.envelope());
    }

    @Test
    void legacyAndSourceOnlyRowsRemainReadableButNeverSpawnable() throws Exception {
        insertProfile("legacy-profile", SOURCE_A);
        insertActiveLostPayload("legacy-profile", """
                {"lastRelocationQueuedAtMs":-11,"lostAtMs":-12,
                 "relocationRetryAttempts":3,"recoveredAtMs":-13}
                """);

        LostRecoveryLoadResult legacy = repository.loadAwaitingByProfile("legacy-profile");

        assertEquals(LostRecoveryLoadResult.Status.LEGACY_UNVERIFIED, legacy.status());
        assertEquals(LostRecoveryLoadResult.Failure.SOURCE_MISSING, legacy.failure());
        assertNull(legacy.envelope().sourceNpcUuid());
        assertEquals(-12L, legacy.envelope().metadata().lostAtMs());
        assertEquals(1, repository.loadAll().size());

        LostRecoveryWriteResult sourceOnly = committed(repository.upsertTracked(lost(SOURCE_B, null)));
        LostRecoveryLoadResult incomplete = repository.loadAwaitingByProfile(sourceOnly.profileId());
        assertEquals(LostRecoveryLoadResult.Status.LEGACY_UNVERIFIED, incomplete.status());
        assertEquals(LostRecoveryLoadResult.Failure.SNAPSHOT_MISSING, incomplete.failure());
        assertEquals(SOURCE_B, incomplete.envelope().sourceNpcUuid());
        assertTrue(activePayload(sourceOnly.profileId()).contains("\"sourceNpcUuid\""));
    }

    @Test
    void sourceAndProfileCurrentMismatchesFailClosed() throws Exception {
        LostRecoveryWriteResult written = committed(
                repository.upsertTracked(lost(SOURCE_A, null), fullSnapshot(SOURCE_A)));
        JsonObject payload = activePayloadObject(written.profileId());
        payload.addProperty("sourceNpcUuid", SOURCE_B.toString());
        updateActivePayload(written.profileId(), payload.toString());

        LostRecoveryLoadResult sourceMismatch = repository.loadAwaitingByProfile(written.profileId());
        assertEquals(LostRecoveryLoadResult.Status.FAILED, sourceMismatch.status());
        assertEquals(LostRecoveryLoadResult.Failure.SOURCE_MISMATCH, sourceMismatch.failure());

        committed(repository.upsertTracked(lost(SOURCE_A, null), fullSnapshot(SOURCE_A)));
        execute("UPDATE npc_profiles SET current_npc_uuid = '" + SOURCE_B
                + "' WHERE profile_id = '" + written.profileId() + "'");
        LostRecoveryLoadResult profileMismatch = repository.loadAwaitingByProfile(written.profileId());
        assertEquals(LostRecoveryLoadResult.Status.FAILED, profileMismatch.status());
        assertEquals(LostRecoveryLoadResult.Failure.PROFILE_CURRENT_MISMATCH,
                profileMismatch.failure());
    }

    @Test
    void replacementEvidenceIsNotReturnedAsAwaitingRecovery() throws Exception {
        LostRecoveryWriteResult written = committed(
                repository.upsertTracked(lost(SOURCE_A, SOURCE_B), fullSnapshot(SOURCE_A)));

        LostRecoveryLoadResult result = repository.loadAwaitingByProfile(written.profileId());

        assertEquals(LostRecoveryLoadResult.Status.NOT_FOUND, result.status());
        assertEquals(LostRecoveryLoadResult.Failure.REPLACEMENT_PRESENT, result.failure());
        assertEquals(SOURCE_B, result.envelope().metadata().replacementNpcUuid());
        assertFalse(result.envelope().isAwaitingRecovery());
    }

    @Test
    void duplicateMalformedAndStrictSnapshotFailuresAreDistinguished() throws Exception {
        LostRecoveryWriteResult written = committed(
                repository.upsertTracked(lost(SOURCE_A, null), fullSnapshot(SOURCE_A)));
        insertActiveLostPayload(written.profileId(), activePayload(written.profileId()));

        LostRecoveryLoadResult duplicate = repository.loadAwaitingByProfile(written.profileId());
        assertEquals(LostRecoveryLoadResult.Status.FAILED, duplicate.status());
        assertEquals(LostRecoveryLoadResult.Failure.DUPLICATE_ACTIVE_ROWS, duplicate.failure());

        deleteNewestActiveSnapshot(written.profileId());
        updateActivePayload(written.profileId(), "{");
        LostRecoveryLoadResult malformed = repository.loadAwaitingByProfile(written.profileId());
        assertEquals(LostRecoveryLoadResult.Failure.INVALID_JSON, malformed.failure());

        committed(repository.upsertTracked(lost(SOURCE_A, null), fullSnapshot(SOURCE_A)));
        JsonObject invalidSnapshot = activePayloadObject(written.profileId());
        invalidSnapshot.getAsJsonObject("fullStateSnapshot")
                .add("happiness", JsonParser.parseString("[]"));
        String nested = invalidSnapshot.getAsJsonObject("fullStateSnapshot").toString();
        invalidSnapshot.addProperty("fullStateSnapshotSha256", sha256(nested));
        updateActivePayload(written.profileId(), invalidSnapshot.toString());

        LostRecoveryLoadResult strictFailure = repository.loadAwaitingByProfile(written.profileId());
        assertEquals(LostRecoveryLoadResult.Status.FAILED, strictFailure.status());
        assertEquals(LostRecoveryLoadResult.Failure.SNAPSHOT_DECODE_FAILED,
                strictFailure.failure());
    }

    @Test
    void sourceLookupFailsClosedWhenUuidMapsToMultipleProfiles() throws Exception {
        LostRecoveryWriteResult written = committed(
                repository.upsertTracked(lost(SOURCE_A, null), fullSnapshot(SOURCE_A)));
        insertProfile("profile-b", SOURCE_B);
        execute("DELETE FROM npc_uuid_aliases WHERE npc_uuid = '" + SOURCE_A + "'");
        execute("INSERT INTO npc_uuid_aliases VALUES ('" + SOURCE_A
                + "', 'profile-b', 0, 1)");

        LostRecoveryLoadResult result = repository.loadAwaitingBySourceUuid(SOURCE_A);

        assertEquals(LostRecoveryLoadResult.Status.FAILED, result.status());
        assertEquals(LostRecoveryLoadResult.Failure.PROFILE_LOOKUP_CONFLICT, result.failure());
        assertEquals(LostRecoveryLoadResult.Status.FOUND,
                repository.loadAwaitingByProfile(written.profileId()).status());
    }

    /** Regression for a recovered companion whose live full-state cache vanished on restart. */
    @Test
    void finalizedRecoveredProjectionProvidesVerifiedRestartSnapshot() throws Exception {
        RecoveredFixture recovered = prepareRecoveredProjection();

        RecoveredProjectionSnapshotLoadResult result =
                repository.loadRecoveredProjectionSnapshot(TARGET_A);

        assertEquals(RecoveredProjectionSnapshotLoadResult.Status.FOUND, result.status());
        assertEquals(recovered.profileId(), result.profileId());
        assertEquals(SOURCE_A, result.sourceNpcUuid());
        assertNotNull(result.snapshot());
        assertEquals(SOURCE_A, result.snapshot().npcUuid());
        assertEquals("tamed_test", result.snapshot().roleId());
        assertEquals(-9_001L, result.snapshot().capturedAtMs());
        assertEquals(RecoveredProjectionSnapshotLoadResult.Status.NOT_FOUND,
                repository.loadRecoveredProjectionSnapshot(SOURCE_A).status());
    }

    @Test
    void recoveredProjectionFallbackRejectsLifecycleAndOperationConflicts() throws Exception {
        RecoveredFixture recovered = prepareRecoveredProjection();

        execute("UPDATE companion_population_state SET lifecycle_state = 'ACTIVE' WHERE profile_id = '"
                + recovered.profileId() + "'");
        RecoveredProjectionSnapshotLoadResult active =
                repository.loadRecoveredProjectionSnapshot(TARGET_A);
        assertEquals(RecoveredProjectionSnapshotLoadResult.Status.CONFLICT, active.status());
        assertEquals("population_not_unloaded", active.reason());

        execute("UPDATE companion_population_state SET lifecycle_state = 'UNLOADED' WHERE profile_id = '"
                + recovered.profileId() + "'");
        insertActiveRecovery(recovered.profileId());
        RecoveredProjectionSnapshotLoadResult recovering =
                repository.loadRecoveredProjectionSnapshot(TARGET_A);
        assertEquals(RecoveredProjectionSnapshotLoadResult.Status.CONFLICT, recovering.status());
        assertEquals("active_recovery_conflict", recovering.reason());

        execute("DELETE FROM npc_recovery_operations WHERE active = 1");
        execute("DELETE FROM npc_recovery_operations WHERE profile_id = '"
                + recovered.profileId() + "' AND state = 'FINALIZED'");
        RecoveredProjectionSnapshotLoadResult unproven =
                repository.loadRecoveredProjectionSnapshot(TARGET_A);
        assertEquals(RecoveredProjectionSnapshotLoadResult.Status.CONFLICT, unproven.status());
        assertEquals("finalized_recovery_evidence_missing", unproven.reason());
    }

    @Test
    void recoveredProjectionFallbackRejectsTamperedDurableSnapshot() throws Exception {
        RecoveredFixture recovered = prepareRecoveredProjection();
        JsonObject payload = activePayloadObject(recovered.profileId());
        payload.getAsJsonObject("fullStateSnapshot").addProperty("capturedAtMs", -999L);
        updateActivePayload(recovered.profileId(), payload.toString());

        RecoveredProjectionSnapshotLoadResult result =
                repository.loadRecoveredProjectionSnapshot(TARGET_A);

        assertEquals(RecoveredProjectionSnapshotLoadResult.Status.FAILED, result.status());
        assertEquals("lost_envelope_snapshot_hash_invalid", result.reason());
        assertNull(result.snapshot());
    }

    private CommandLinkedNpcLostService.LostLinkedNpcSnapshot lost(UUID source, UUID replacement) {
        return new CommandLinkedNpcLostService.LostLinkedNpcSnapshot(
                source,
                new Vector3d(1.0, 2.0, 3.0),
                new Vector3d(4.0, 5.0, 6.0),
                -201L,
                -202L,
                4,
                replacement,
                -203L
        );
    }

    private CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot(UUID source) {
        return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                source,
                "coop_test",
                2,
                "tamed_test",
                null,
                null,
                null,
                null,
                new TameworkHappinessComponent("happy", 0.75, -101L),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0.5,
                -9_001L
        );
    }

    private LostRecoveryWriteResult committed(
            PersistenceWriteQueue.WriteSubmission<LostRecoveryWriteResult> submission) throws Exception {
        return committedValue(submission);
    }

    private <T> T committedValue(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome =
                submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        assertNotNull(outcome.value());
        return outcome.value();
    }

    private RecoveredFixture prepareRecoveredProjection() throws Exception {
        LostRecoveryWriteResult written = committed(
                repository.upsertTracked(lost(SOURCE_A, null), fullSnapshot(SOURCE_A)));
        NpcRecoveryOperationRepository recoveryRepository =
                new NpcRecoveryOperationRepository(connections, writeQueue, () -> 300L);
        String operationId = "restart-fallback-operation";
        var claimed = committedValue(recoveryRepository.claim(
                new NpcRecoveryOperationRepository.RecoveryClaim(
                        operationId, written.profileId(), SOURCE_A, TARGET_A)));
        assertEquals(NpcRecoveryOperationRepository.ClaimStatus.CLAIMED, claimed.status());
        var projected = committedValue(recoveryRepository.recordProjectionCreated(
                operationId, written.profileId(), TARGET_A, 0L));
        assertEquals(NpcRecoveryOperationRepository.TransitionStatus.APPLIED, projected.status());
        var finalized = committedValue(recoveryRepository.finalizeRecovery(
                new NpcRecoveryOperationRepository.RecoveryFinalization(
                        operationId,
                        written.profileId(),
                        SOURCE_A,
                        TARGET_A,
                        TARGET_A,
                        1L,
                        List.of()
                )));
        assertEquals(NpcRecoveryOperationRepository.TransitionStatus.APPLIED, finalized.status());
        insertPopulationState(written.profileId(), "UNLOADED");
        return new RecoveredFixture(written.profileId());
    }

    private void insertPopulationState(String profileId, String lifecycle) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO companion_population_state (
                         profile_id, ownership_world_name, lifecycle_state,
                         physical_world_name, physical_chunk_x, physical_chunk_z,
                         revision, source, created_at_ms, updated_at_ms
                     ) VALUES (?, 'source-world', ?, NULL, NULL, NULL, 1, 'test', 1, 1)
                     """)) {
            statement.setString(1, profileId);
            statement.setString(2, lifecycle);
            statement.executeUpdate();
        }
    }

    private void insertActiveRecovery(String profileId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO npc_recovery_operations (
                         operation_id, profile_id, source_npc_uuid, planned_target_uuid,
                         state, active, generation, attempt_count, created_at_ms, updated_at_ms
                     ) VALUES ('conflicting-recovery', ?, ?, ?, 'PREPARED', 1, 0, 0, 2, 2)
                     """)) {
            statement.setString(1, profileId);
            statement.setString(2, TARGET_A.toString());
            statement.setString(3, uuid(102L).toString());
            statement.executeUpdate();
        }
    }

    private void insertProfile(String profileId, UUID currentUuid) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO npc_profiles (
                         profile_id, current_npc_uuid, role_id,
                         created_at_ms, updated_at_ms, last_active_at_ms
                     ) VALUES (?, ?, 'Mob_Test', 1, 1, 1)
                     """)) {
            statement.setString(1, profileId);
            statement.setString(2, currentUuid.toString());
            statement.executeUpdate();
        }
    }

    private void insertActiveLostPayload(String profileId, String payload) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO npc_snapshots (
                         profile_id, snapshot_type, snapshot_version,
                         payload_json, is_active, created_at_ms
                     ) VALUES (?, 'lost', 1, ?, 1, 2)
                     """)) {
            statement.setString(1, profileId);
            statement.setString(2, payload);
            statement.executeUpdate();
        }
    }

    private String activePayload(String profileId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT payload_json FROM npc_snapshots
                     WHERE profile_id = ? AND snapshot_type = 'lost' AND is_active = 1
                     ORDER BY snapshot_id LIMIT 1
                     """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private long activeCreatedAt(String profileId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT created_at_ms FROM npc_snapshots
                     WHERE profile_id = ? AND snapshot_type = 'lost' AND is_active = 1
                     ORDER BY snapshot_id LIMIT 1
                     """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private JsonObject activePayloadObject(String profileId) throws Exception {
        return JsonParser.parseString(activePayload(profileId)).getAsJsonObject();
    }

    private void updateActivePayload(String profileId, String payload) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE npc_snapshots SET payload_json = ?
                     WHERE profile_id = ? AND snapshot_type = 'lost' AND is_active = 1
                     """)) {
            statement.setString(1, payload);
            statement.setString(2, profileId);
            statement.executeUpdate();
        }
    }

    private void deleteNewestActiveSnapshot(String profileId) throws Exception {
        execute("DELETE FROM npc_snapshots WHERE snapshot_id = (SELECT MAX(snapshot_id) "
                + "FROM npc_snapshots WHERE profile_id = '" + profileId + "' AND is_active = 1)");
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            result.append(Character.forDigit((current >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(current & 0x0f, 16));
        }
        return result.toString();
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private record RecoveredFixture(String profileId) {
    }
}

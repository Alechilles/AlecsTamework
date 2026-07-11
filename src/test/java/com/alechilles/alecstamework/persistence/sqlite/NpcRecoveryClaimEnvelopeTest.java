package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ClaimStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryClaim;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for durable lost-envelope authorization before a recovery spawn claim. */
class NpcRecoveryClaimEnvelopeTest {
    private static final UUID SOURCE_A = uuid(1L);
    private static final UUID SOURCE_B = uuid(2L);
    private static final UUID TARGET_A = uuid(101L);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue writeQueue;
    private NpcRecoveryOperationRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("recovery-claim-envelope.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProfile(connection, "profile-a", SOURCE_A);
            insertLostState(connection, "profile-a");
            insertLostSnapshot(connection, "profile-a",
                    RecoveryTestEnvelopeFixtures.validEnvelope(SOURCE_A));
        }
        writeQueue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null);
        repository = new NpcRecoveryOperationRepository(connections, writeQueue, () -> 100L);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void validEnvelopeClaimsOnceAndExactOperationReplays() throws Exception {
        RecoveryClaim claim = claim("operation-a");

        assertEquals(ClaimStatus.CLAIMED, committed(claim).status());
        execute("UPDATE profile_states SET capture_active = 1 WHERE profile_id = 'profile-a'");
        assertEquals(ClaimStatus.REPLAYED, committed(claim).status());
        assertEquals(1, operationCount());
    }

    @Test
    void legacyAndSourceOnlyEnvelopesNeverClaim() throws Exception {
        replacePayload("{}");
        assertRejected("legacy", ClaimStatus.LOST_ENVELOPE_UNVERIFIED);

        replacePayload(RecoveryTestEnvelopeFixtures.sourceOnlyEnvelope(SOURCE_A));
        assertRejected("source-only", ClaimStatus.LOST_ENVELOPE_UNVERIFIED);
    }

    @Test
    void malformedAndTamperedEnvelopesNeverClaim() throws Exception {
        replacePayload("{");
        assertRejected("malformed", ClaimStatus.LOST_ENVELOPE_INVALID);

        JsonObject tampered = JsonParser.parseString(
                RecoveryTestEnvelopeFixtures.validEnvelope(SOURCE_A)).getAsJsonObject();
        tampered.getAsJsonObject("fullStateSnapshot").addProperty("capturedAtMs", -999L);
        replacePayload(tampered.toString());
        assertRejected("tampered", ClaimStatus.LOST_ENVELOPE_INVALID);
    }

    @Test
    void strictSnapshotDecodeFailureNeverClaimsEvenWithMatchingHash() throws Exception {
        JsonObject invalid = JsonParser.parseString(
                RecoveryTestEnvelopeFixtures.validEnvelope(SOURCE_A)).getAsJsonObject();
        invalid.getAsJsonObject("fullStateSnapshot")
                .add("owner", JsonParser.parseString("[]"));
        String nested = invalid.getAsJsonObject("fullStateSnapshot").toString();
        invalid.addProperty("fullStateSnapshotSha256", sha256(nested));
        replacePayload(invalid.toString());

        assertRejected("decode-failure", ClaimStatus.LOST_ENVELOPE_INVALID);
    }

    @Test
    void fullSnapshotWithoutRoleNeverClaimsEvenWithMatchingHash() throws Exception {
        JsonObject missingRole = JsonParser.parseString(
                RecoveryTestEnvelopeFixtures.validEnvelope(SOURCE_A)).getAsJsonObject();
        missingRole.getAsJsonObject("fullStateSnapshot").remove("roleId");
        String nested = missingRole.getAsJsonObject("fullStateSnapshot").toString();
        missingRole.addProperty("fullStateSnapshotSha256", sha256(nested));
        replacePayload(missingRole.toString());

        assertRejected("missing-role", ClaimStatus.LOST_ENVELOPE_INVALID);
    }

    @Test
    void sourceAndCurrentUuidMismatchesNeverClaim() throws Exception {
        replacePayload(RecoveryTestEnvelopeFixtures.validEnvelope(SOURCE_B));
        assertRejected("source-mismatch", ClaimStatus.SOURCE_CONFLICT);

        replacePayload(RecoveryTestEnvelopeFixtures.validEnvelope(SOURCE_A));
        execute("UPDATE npc_profiles SET current_npc_uuid = '" + SOURCE_B
                + "' WHERE profile_id = 'profile-a'");
        assertRejected("current-mismatch", ClaimStatus.SOURCE_CONFLICT);
    }

    @Test
    void replacementAndDuplicateActiveRowsNeverClaim() throws Exception {
        replacePayload(RecoveryTestEnvelopeFixtures.recoveredEnvelope(SOURCE_A, SOURCE_B));
        assertRejected("replacement", ClaimStatus.LOST_NOT_AWAITING);

        replacePayload(RecoveryTestEnvelopeFixtures.validEnvelope(SOURCE_A));
        try (Connection connection = connections.openConnection()) {
            insertLostSnapshot(connection, "profile-a",
                    RecoveryTestEnvelopeFixtures.validEnvelope(SOURCE_A));
        }
        assertRejected("duplicate", ClaimStatus.LOST_SNAPSHOT_CONFLICT);
    }

    private void assertRejected(String operationId, ClaimStatus status) throws Exception {
        var result = committed(claim(operationId));
        assertEquals(status, result.status());
        assertNull(result.operation());
        assertEquals(0, operationCount());
    }

    private NpcRecoveryOperationRepository.ClaimResult committed(RecoveryClaim claim) throws Exception {
        PersistenceWriteQueue.WriteSubmission<NpcRecoveryOperationRepository.ClaimResult> submission =
                repository.claim(claim);
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<NpcRecoveryOperationRepository.ClaimResult> outcome =
                submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        return outcome.value();
    }

    private RecoveryClaim claim(String operationId) {
        return new RecoveryClaim(operationId, "profile-a", SOURCE_A, TARGET_A);
    }

    private int operationCount() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM npc_recovery_operations")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void replacePayload(String payload) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE npc_snapshots SET payload_json = ?
                     WHERE profile_id = 'profile-a' AND snapshot_type = 'lost' AND is_active = 1
                     """)) {
            statement.setString(1, payload);
            statement.executeUpdate();
        }
    }

    private void insertProfile(Connection connection, String profileId, UUID currentUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
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

    private void insertLostState(Connection connection, String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO profile_states (
                    profile_id, capture_active, death_active, lost_active, in_coop, updated_at_ms
                ) VALUES (?, 0, 0, 1, 0, 1)
                """)) {
            statement.setString(1, profileId);
            statement.executeUpdate();
        }
    }

    private void insertLostSnapshot(Connection connection,
                                    String profileId,
                                    String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_snapshots (
                    profile_id, snapshot_type, snapshot_version,
                    payload_json, is_active, created_at_ms
                ) VALUES (?, 'lost', 1, ?, 1, 1)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, payload);
            statement.executeUpdate();
        }
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
}

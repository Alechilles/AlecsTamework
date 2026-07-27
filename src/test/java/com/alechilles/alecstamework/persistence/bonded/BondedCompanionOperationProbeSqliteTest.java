package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite regressions for policy-independent bonded-operation recovery probes. */
class BondedCompanionOperationProbeSqliteTest {
    private static final UUID OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final String CALLER = "revive-panel";
    private static final String KEY = "request-42";

    @TempDir
    Path tempDir;

    private Path database;
    private BondedCompanionStore store;

    @BeforeEach
    void setUp() {
        database = tempDir.resolve("bonded-companions.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        store = new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation("provision", '1',
                                BondedCompanionOperation.Type.PROVISION),
                        profile()).code());
    }

    @Test
    void terminalIdentityProbeIgnoresCurrentRequestHashAndProfilePolicy()
            throws Exception {
        BondedCompanionOperation original = operation(
                KEY, 'a', BondedCompanionOperation.Type.REVIVE);
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> terminal =
                store.reviveProfile(original, 0L, -9_000L);
        assertEquals(BondedCompanionStoreResult.Code.INVALID_STATE,
                terminal.code());

        replaceCurrentPolicy("{\"revive\":\"disabled\"}");
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> changedHash =
                store.reviveProfile(operation(
                        KEY, 'b', BondedCompanionOperation.Type.REVIVE),
                        0L, -8_000L);
        assertEquals(BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT,
                changedHash.code());

        var recovered = store.findProfileOperationByIdentity(probe(OWNER))
                .orElseThrow();
        assertEquals(terminal.code(), recovered.code());
        assertEquals(terminal.reason(), recovered.reason());
        assertTrue(recovered.replayed());

        var wrongScope = store.findProfileOperationByIdentity(probe(OTHER_OWNER))
                .orElseThrow();
        assertEquals(BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT,
                wrongScope.code());
        assertFalse(wrongScope.replayed());

        var wrongRevision = store.findProfileOperationByIdentity(
                probe(OWNER, 1L)).orElseThrow();
        assertEquals(BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT,
                wrongRevision.code());
        assertFalse(wrongRevision.replayed());

        assertEquals(Long.MAX_VALUE, operationExpiry());
        assertEquals(1, store.pruneOperations(Long.MAX_VALUE, 8));
        assertEquals(Long.MAX_VALUE, operationExpiry());
        assertThrows(IllegalArgumentException.class,
                () -> store.markProfileOperationPaymentSettled(
                        probe(OWNER), false, Long.MAX_VALUE));
        assertFalse(store.markProfileOperationPaymentSettled(
                probeWithoutRevision(), false, 10_000L));
        assertFalse(store.markProfileOperationPaymentSettled(
                probe(OWNER), true, 10_000L));
        assertTrue(store.markProfileOperationPaymentSettled(
                probe(OWNER), false, 10_000L));
        assertTrue(store.markProfileOperationPaymentSettled(
                probe(OWNER), false, 20_000L));
        assertEquals(10_000L, operationExpiry());
        assertEquals(1, store.pruneOperations(Long.MAX_VALUE, 8));
    }

    @Test
    void schemaRejectsCommittedPendingOperationRows() throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO bonded_companion_operation(
                         caller_namespace, idempotency_key, owner_uuid,
                         roster_id, profile_id, operation_type, request_hash,
                         operation_state, result_json, created_at_ms,
                         updated_at_ms, expires_at_ms, expected_revision
                     ) VALUES (?, ?, ?, ?, ?, 'STORE', ?, 'PENDING', NULL,
                               ?, ?, ?, ?)
                     """)) {
            insert.setString(1, CALLER);
            insert.setString(2, "forbidden-pending");
            insert.setString(3, OWNER.toString());
            insert.setString(4, "roster-a");
            insert.setString(5, "profile-a");
            insert.setString(6, "c".repeat(64));
            insert.setLong(7, -10_000L);
            insert.setLong(8, -10_000L);
            insert.setLong(9, 10_000L);
            insert.setLong(10, 0L);
            assertThrows(java.sql.SQLException.class, insert::executeUpdate);
        }
    }

    private void replaceCurrentPolicy(String policyJson) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE bonded_companion_profile
                     SET policy_json = ?
                     WHERE profile_id = 'profile-a'
                     """)) {
            update.setString(1, policyJson);
            assertEquals(1, update.executeUpdate());
        }
    }

    private BondedCompanionOperationProbe probe(UUID ownerUuid) {
        return probe(ownerUuid, 0L);
    }

    private BondedCompanionOperationProbe probeWithoutRevision() {
        return new BondedCompanionOperationProbe(
                CALLER, KEY, OWNER, "roster-a", "profile-a",
                BondedCompanionOperation.Type.REVIVE);
    }

    private BondedCompanionOperationProbe probe(
            UUID ownerUuid, long expectedRevision) {
        return new BondedCompanionOperationProbe(
                CALLER, KEY, ownerUuid, "roster-a", "profile-a",
                BondedCompanionOperation.Type.REVIVE, expectedRevision);
    }

    private BondedCompanionOperation operation(
            String key,
            char hash,
            BondedCompanionOperation.Type type
    ) {
        return new BondedCompanionOperation(
                CALLER, key, String.valueOf(hash).repeat(64), OWNER,
                "roster-a", "profile-a", type, -10_000L,
                type == BondedCompanionOperation.Type.REVIVE
                        ? Long.MAX_VALUE : 10_000L);
    }

    private BondedCompanionRecord.Profile profile() {
        return new BondedCompanionRecord.Profile(
                "profile-a", OWNER, "roster-a", "family:wolf",
                "role:companion", BondedCompanionState.STORED, 0,
                BondedCompanionPayload.of(
                        "full-snapshot".getBytes(StandardCharsets.UTF_8)),
                -10_000L, -10_000L, Map.of("revive", "enabled"),
                "Wolf", "Wolf", "Female", null, 0L, 0L,
                null, null);
    }

    private long operationExpiry() throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT expires_at_ms FROM bonded_companion_operation
                     WHERE caller_namespace = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, CALLER);
            statement.setString(2, KEY);
            try (java.sql.ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getLong(1);
            }
        }
    }
}

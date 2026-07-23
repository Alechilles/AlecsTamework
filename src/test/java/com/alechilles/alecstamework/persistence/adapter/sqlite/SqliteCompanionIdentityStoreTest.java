package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction-level integration tests for the replacement identity adapter. */
class SqliteCompanionIdentityStoreTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS_A =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS_B =
            NpcAlias.parse("30000000-0000-0000-0000-000000000002");
    private static final OperationId OPERATION_A =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION_B =
            OperationId.parse("40000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void createsAndRevisionFencesImmutableProfiles() throws Exception {
        try (Connection connection = transaction()) {
            SqliteCompanionIdentityStore store = new SqliteCompanionIdentityStore(connection);
            CompanionIdentity initial = profile(0, "First", -9_000);
            assertTrue(store.createProfile(initial).applied());
            assertTrue(store.createProfile(initial).applied());
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.createProfile(profile(0, "Different", -9_000)).status()
            );

            CompanionIdentity staleUpdate = profile(2, "Stale", -8_500);
            assertEquals(
                    PersistenceMutationStatus.REVISION_MISMATCH,
                    store.updateProfile(staleUpdate, 1).status()
            );
            CompanionIdentity updated = profile(1, "Updated", -8_000);
            assertEquals(updated, store.updateProfile(updated, 0).value());
            assertEquals(updated, store.findProfile(PROFILE).orElseThrow());
            connection.commit();
        }
    }

    @Test
    void leasesAndPromotesAliasesThroughTheExactOperationFence() throws Exception {
        try (Connection connection = transaction()) {
            SqliteCompanionIdentityStore store = new SqliteCompanionIdentityStore(connection);
            store.createProfile(profile(0, "Companion", -9_000));
            insertOperation(connection, OPERATION_A);
            insertOperation(connection, OPERATION_B);

            CompanionAlias firstLease =
                    store.leaseAlias(PROFILE, ALIAS_A, OPERATION_A, -8_000).value();
            assertEquals(0, firstLease.generation());
            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.promoteAlias(ALIAS_A, OPERATION_B, -7_000).status()
            );
            assertTrue(store.promoteAlias(ALIAS_A, OPERATION_A, -7_000).applied());

            CompanionAlias secondLease =
                    store.leaseAlias(PROFILE, ALIAS_B, OPERATION_B, -6_000).value();
            assertEquals(1, secondLease.generation());
            assertTrue(store.promoteAlias(ALIAS_B, OPERATION_B, -5_000).applied());

            CompanionAlias retiredFirst = store.resolveAlias(ALIAS_A).orElseThrow();
            assertEquals(CompanionAlias.State.RETIRED, retiredFirst.state());
            assertEquals(-5_000, retiredFirst.retiredAtMs());
            assertEquals(ALIAS_B, store.findCurrentAlias(PROFILE).orElseThrow().alias());
            connection.commit();
        }
    }

    @Test
    void preservesAliasUniquenessAndToolLinkCreationTime() throws Exception {
        UUID toolId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        try (Connection connection = transaction()) {
            SqliteCompanionIdentityStore store = new SqliteCompanionIdentityStore(connection);
            store.createProfile(profile(0, "Companion", -9_000));
            insertOperation(connection, OPERATION_A);
            assertTrue(store.leaseAlias(PROFILE, ALIAS_A, OPERATION_A, -8_000).applied());
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.leaseAlias(PROFILE, ALIAS_A, OPERATION_B, -7_000).status()
            );

            CompanionToolLink initial =
                    new CompanionToolLink(PROFILE, toolId, "command", -7_000, -7_000);
            CompanionToolLink updated =
                    new CompanionToolLink(PROFILE, toolId, "command", -6_000, -5_000);
            assertTrue(store.linkTool(initial).applied());
            CompanionToolLink stored = store.linkTool(updated).value();
            assertEquals(-7_000, stored.createdAtMs());
            assertEquals(-5_000, stored.updatedAtMs());
            assertEquals(java.util.List.of(stored), store.findToolLinks(PROFILE));
            connection.commit();
        }
    }

    @Test
    void neverCommitsBehindTheOwningTransaction() throws Exception {
        try (Connection connection = transaction()) {
            SqliteCompanionIdentityStore store = new SqliteCompanionIdentityStore(connection);
            assertTrue(store.createProfile(profile(0, "Rolled Back", -9_000)).applied());
            connection.rollback();
        }

        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM companion_profile WHERE profile_id = ?")) {
            statement.setString(1, PROFILE.toString());
            try (ResultSet row = statement.executeQuery()) {
                assertFalse(row.next());
            }
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private CompanionIdentity profile(long revision, String name, long updatedAt) throws Exception {
        String json = "{\"source\":\"test\"}";
        return new CompanionIdentity(
                PROFILE, name, "role", json, Sha256Hash.ofUtf8(json), "world",
                -10_000, updatedAt, updatedAt, revision
        );
    }

    private void insertOperation(Connection connection, OperationId operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operation_envelope(
                    operation_id, idempotency_key, operation_kind, payload_version,
                    payload_json, phase, feature_scope, expected_lifecycle_revision,
                    lease_owner, lease_until_ms, attempt_count, failure_kind, failure_code,
                    created_at_ms, updated_at_ms, durable_at_ms, published_at_ms, terminal_at_ms
                ) VALUES (?, ?, 'identity_test', 1, '{}', 'PREPARED', 'test',
                          NULL, NULL, 0, 0, NULL, NULL, -10000, -10000, NULL, NULL, NULL)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }
}

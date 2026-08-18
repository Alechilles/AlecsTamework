package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.persistence.activation.PersistenceActivationMode;
import com.alechilles.alecstamework.persistence.activation.TameworkPersistenceActivationEvidence;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.runtime.activation.TameworkActivationEvidence;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlanner;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModuleCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for the isolated bonded persistence activation probe. */
class BondedCompanionPersistenceActivationProbeTest {
    private static final String OWNER =
            "10000000-0000-0000-0000-000000000005";
    private static final String PROFILE = "profile-1";

    @TempDir
    Path temporaryDirectory;

    /** Regression: an unused bonded authority must not create its SQLite file. */
    @Test
    void missingDatabaseIsDormantAndRemainsAbsent() throws Exception {
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);

        TameworkPersistenceActivationEvidence evidence =
                probe(database);

        assertEquals(PersistenceActivationMode.DORMANT, evidence.mode());
        assertFalse(evidence.databasePresent());
        assertFalse(Files.exists(database));
    }

    /** Regression: a valid empty bonded schema must not start bonded workers. */
    @Test
    void emptyValidSchemaIsDormant() throws Exception {
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        initializeSchema(database);

        TameworkPersistenceActivationEvidence evidence =
                probe(database);

        assertEquals(PersistenceActivationMode.DORMANT, evidence.mode());
        assertTrue(evidence.databasePresent());
        assertTrue(evidence.schemaValid());
        assertFalse(evidence.hasDurableWork());
    }

    /** Regression: a normal bonded WAL must wake its isolated recovery path. */
    @Test
    void validWalSidecarActivatesRecovery() throws Exception {
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        initializeSchema(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("PRAGMA user_version=1");
            assertTrue(Files.isRegularFile(database.resolveSibling(
                    BondedCompanionDataPath.FILE_NAME + "-wal")));

            TameworkPersistenceActivationEvidence evidence = probe(database);

            assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
            assertTrue(evidence.evidence().contains("bonded-wal-recovery"));
        }
    }

    /** Regression: bonded profiles activate bonded authority without generic state. */
    @Test
    void bondedProfileActivatesOnlyBondedAuthority() throws Exception {
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        initializeSchema(database);
        insertProfile(database);

        TameworkPersistenceActivationEvidence evidence =
                probe(database);

        assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
        assertTrue(evidence.hasDurableWork());
        assertTrue(evidence.evidence().contains("bonded-profile"));
    }

    /** Regression: pending bonded cleanup must not be stranded by a dormant decision. */
    @Test
    void pendingCleanupActivatesBondedAuthority() throws Exception {
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        initializeSchema(database);
        insertProfile(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bonded_companion_cleanup(
                         cleanup_id, owner_uuid, roster_id, profile_id,
                         target_kind, target_npc_uuid, cleanup_reason,
                         cleanup_state, attempt_count, next_attempt_at_ms,
                         created_at_ms, retained_until_ms, world_key
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, "cleanup-1");
            statement.setString(2, OWNER);
            statement.setString(3, "hydragon:dragons");
            statement.setString(4, PROFILE);
            statement.setString(5, "PROJECTION");
            statement.setString(6, "20000000-0000-0000-0000-000000000005");
            statement.setString(7, "test");
            statement.setString(8, "PENDING");
            statement.setInt(9, 0);
            statement.setLong(10, 1L);
            statement.setLong(11, 1L);
            statement.setLong(12, 2L);
            statement.setString(13, "world");
            statement.executeUpdate();
        }

        TameworkPersistenceActivationEvidence evidence =
                probe(database);

        assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
        assertTrue(evidence.evidence().contains("pending-cleanup"));
    }

    /** Regression: an unreadable bonded file must be read-only, never fresh-initialized. */
    @Test
    void malformedExistingDatabaseIsReadOnly() throws Exception {
        Path database = temporaryDirectory.resolve(
                BondedCompanionDataPath.FILE_NAME);
        Files.writeString(database, "not a sqlite database");

        TameworkPersistenceActivationEvidence evidence =
                probe(database);

        assertEquals(PersistenceActivationMode.READ_ONLY, evidence.mode());
        assertTrue(evidence.databasePresent());
        assertFalse(evidence.schemaValid());
    }

    /** Regression: dormant composition returns before resolving runtime dependencies. */
    @Test
    void dormantCompositionDoesNotConstructBondedRuntime() {
        var plan = new TameworkRuntimeActivationPlanner(
                TameworkRuntimeModuleCatalog.standard()).plan(
                        TameworkActivationEvidence.empty());

        assertNull(TameworkBondedCompanionComposition.openIfActive(
                null, null, null, null, null, null, plan,
                TameworkPersistenceActivationEvidence.dormant(false, false)
        ));
    }

    private TameworkPersistenceActivationEvidence probe(Path database) {
        return new BondedCompanionPersistenceActivationProbe(database).probe();
    }

    private void initializeSchema(Path database) {
        BondedCompanionSchemaManager manager =
                new BondedCompanionSchemaManager(database, () -> 1L);
        assertTrue(manager.initialize().availability().available());
    }

    private void insertProfile(Path database) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO bonded_companion_profile(
                         profile_id, owner_uuid, roster_id, family_id, role_id,
                         state, revision, snapshot_json, created_at_ms,
                         updated_at_ms, policy_json, summon_cooldown_until_ms,
                         revive_count
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, PROFILE);
            statement.setString(2, OWNER);
            statement.setString(3, "hydragon:dragons");
            statement.setString(4, "dragon");
            statement.setString(5, "Mob_Dragon");
            statement.setString(6, "STORED");
            statement.setInt(7, 0);
            statement.setString(8,
                    "{\"encoding\":\"base64\",\"payload\":\"AQ==\"}");
            statement.setLong(9, 1L);
            statement.setLong(10, 1L);
            statement.setString(11, "{}");
            statement.setLong(12, 0L);
            statement.setInt(13, 0);
            statement.executeUpdate();
        }
    }
}

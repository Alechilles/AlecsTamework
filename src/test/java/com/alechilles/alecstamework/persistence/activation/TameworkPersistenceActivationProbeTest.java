package com.alechilles.alecstamework.persistence.activation;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV2Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReleasedRoutedV2Gateway;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.runtime.activation
        .TameworkActivationEvidence;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeActivationPlanner;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeModuleCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for the bounded generic persistence activation probe. */
class TameworkPersistenceActivationProbeTest {
    @TempDir
    Path temporaryDirectory;

    /** Regression: an unused server must not create a database or recovery worker. */
    @Test
    void missingDatabaseIsDormantAndRemainsAbsent() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.DORMANT, evidence.mode());
        assertFalse(evidence.databasePresent());
        assertFalse(Files.exists(database));
    }

    /** Regression: a valid but empty schema must not open persistence workers. */
    @Test
    void emptyValidSchemaIsDormant() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeSchema(database);

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.DORMANT, evidence.mode());
        assertTrue(evidence.databasePresent());
        assertTrue(evidence.schemaValid());
        assertFalse(evidence.hasDurableWork());
    }

    /** Regression: a valid v1 target must enter the normal upgrade path. */
    @Test
    void validV1SchemaActivatesUpgradeWithoutDurableRows() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeV1Schema(database);

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
        assertTrue(evidence.mutationAllowed());
        assertTrue(evidence.schemaValid());
        assertTrue(evidence.evidence().contains(
                "persistence-schema-upgrade-v1"));
    }

    /** Regression: the released routed-read v2 target must enter the upgrade path. */
    @Test
    void releasedRoutedV2SchemaActivatesCompatibilityUpgrade() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeReleasedRoutedV2Schema(database);

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
        assertTrue(evidence.mutationAllowed());
        assertTrue(evidence.schemaValid());
        assertTrue(evidence.evidence().contains(
                "persistence-schema-upgrade-released-v2"));
        assertEquals(1, queryInt(database,
                "SELECT COUNT(*) FROM schema_history"));
    }

    /** Regression: a released-shaped target with the wrong hash remains read-only. */
    @Test
    void releasedRoutedV2SchemaWithWrongHashRemainsReadOnly() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeReleasedRoutedV2Schema(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE schema_history SET schema_hash = '"
                    + "0".repeat(64) + "'");
        }

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.READ_ONLY, evidence.mode());
        assertFalse(evidence.mutationAllowed());
        assertFalse(evidence.evidence().contains(
                "persistence-schema-upgrade-released-v2"));
    }

    /** Regression: an invalid v1-shaped target must not be migrated by probing. */
    @Test
    void invalidV1TargetRemainsReadOnlyWithoutMigration() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeV1Schema(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX idx_projection_outbox_aggregate");
        }

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.READ_ONLY, evidence.mode());
        assertFalse(evidence.mutationAllowed());
        assertEquals(1, queryInt(database,
                "SELECT COUNT(*) FROM schema_history"));
        assertEquals(1, queryInt(database,
                "SELECT version FROM schema_history"));
    }

    /** Regression: a normal WAL must wake recovery instead of becoming read-only. */
    @Test
    void validWalSidecarActivatesRecovery() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeSchema(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("PRAGMA user_version=1");
            assertTrue(Files.isRegularFile(database.resolveSibling(
                    "tamework-state.sqlite-wal")));

            TameworkPersistenceActivationEvidence evidence = probe(database);

            assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
            assertTrue(evidence.evidence().contains(
                    "persistence-wal-recovery"));
        }
    }

    /** Regression: unfinished durable work must keep the complete recovery authority active. */
    @Test
    void nonterminalOperationActivatesGenericAuthority() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeSchema(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO operation_envelope(
                         operation_id, idempotency_key, operation_kind,
                         payload_version, payload_json, phase, feature_scope,
                         created_at_ms, updated_at_ms
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, "operation-1");
            statement.setString(2, "idempotency-1");
            statement.setString(3, "animal-husbandry");
            statement.setInt(4, 1);
            statement.setString(5, "{}");
            statement.setString(6, "DURABLE");
            statement.setString(7, "breeding");
            statement.setLong(8, 1L);
            statement.setLong(9, 1L);
            statement.executeUpdate();
        }

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
        assertTrue(evidence.hasDurableWork());
        assertTrue(evidence.evidence().contains("nonterminal-operation"));
    }

    /** Regression: quarantine and projection evidence must not be mistaken for an empty store. */
    @Test
    void containmentAndProjectionEvidenceActivateGenericAuthority() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeSchema(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO operation_envelope(
                        operation_id, idempotency_key, operation_kind,
                        payload_version, payload_json, phase, feature_scope,
                        created_at_ms, updated_at_ms
                    ) VALUES ('operation-1', 'idempotency-1', 'projection-test',
                        1, '{}', 'PUBLISHED', 'breeding', 1, 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO persistence_incident(
                        incident_id, failure_kind, failure_code, state,
                        summary, evidence_json, created_at_ms
                    ) VALUES ('incident-1', 'STORAGE', 'test', 'OPEN',
                        'test', '{}', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO persistence_quarantine(
                        scope_type, scope_key, incident_id, state,
                        reason_code, created_at_ms
                    ) VALUES ('PROFILE', 'profile-1', 'incident-1', 'ACTIVE',
                        'test', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO feature_circuit(
                        feature_id, state, failure_count, updated_at_ms
                    ) VALUES ('breeding', 'HALF_OPEN', 1, 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO projection_outbox(
                        operation_id, event_type, aggregate_id,
                        aggregate_revision, payload_version, payload_json,
                        created_at_ms
                    ) VALUES ('operation-1', 'PROFILE_CHANGED', 'profile-1',
                        1, 1, '{}', 1)
                    """);
        }

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
        assertTrue(evidence.evidence().contains("active-quarantine"));
        assertTrue(evidence.evidence().contains("open-or-half-open-circuit"));
        assertTrue(evidence.evidence().contains("unresolved-projection"));
    }

    /** Regression: corrupt existing evidence must remain available only for diagnostics. */
    @Test
    void malformedExistingDatabaseIsReadOnly() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        Files.createDirectories(database.getParent());
        Files.writeString(database, "not a sqlite database");

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.READ_ONLY, evidence.mode());
        assertTrue(evidence.databasePresent());
        assertFalse(evidence.schemaValid());
        assertFalse(evidence.hasDurableWork());
    }

    /** Regression: an orphan WAL is uncertain evidence, not an absent target. */
    @Test
    void orphanSidecarIsReadOnlyBeforeMissingDatabaseDecision() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        Files.writeString(database.resolveSibling("tamework-state.sqlite-wal"),
                "orphan");

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.READ_ONLY, evidence.mode());
        assertTrue(evidence.databasePresent());
        assertFalse(Files.exists(database));
    }

    /** Regression: a non-regular target path cannot be treated as dormant. */
    @Test
    void nonRegularExistingTargetIsReadOnly() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        Files.createDirectory(database);

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.READ_ONLY, evidence.mode());
        assertTrue(evidence.databasePresent());
    }

    /** Regression: a dropped required index cannot activate a writer. */
    @Test
    void droppedRequiredIndexIsReadOnly() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeSchema(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX idx_projection_outbox_aggregate");
        }

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.READ_ONLY, evidence.mode());
        assertFalse(evidence.schemaValid());
    }

    /** Regression: caught-up projection rows do not keep an authority active. */
    @Test
    void caughtUpProjectionIsDormant() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeSchema(database);
        insertPublishedProjection(database);
        acknowledgeAllProjectionConsumers(database);

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.DORMANT, evidence.mode());
        assertFalse(evidence.evidence().contains("unresolved-projection"));
    }

    /** Regression: a missing canonical checkpoint keeps projection recovery active. */
    @Test
    void behindProjectionActivatesForCanonicalConsumers() throws Exception {
        Path database = temporaryDirectory.resolve("tamework-state.sqlite");
        initializeSchema(database);
        insertPublishedProjection(database);

        TameworkPersistenceActivationEvidence evidence = probe(database);

        assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
        assertTrue(evidence.evidence().contains("unresolved-projection"));
    }

    /** Regression: an old source in the path layout is active without target creation. */
    @Test
    void importableHistoricalSourceActivatesWithoutCreatingTarget() throws Exception {
        Path targetDirectory = Files.createDirectory(
                temporaryDirectory.resolve("target"));
        Path legacyDirectory = Files.createDirectory(
                temporaryDirectory.resolve("legacy"));
        Files.writeString(legacyDirectory.resolve("CommandLinkedNpcCaptures.dat"),
                "");
        TameworkDataPathLayout layout = new TameworkDataPathLayout(
                targetDirectory, legacyDirectory, Optional.empty());

        TameworkPersistenceActivationEvidence evidence =
                new TameworkPersistenceActivationProbe(layout).probe();

        assertEquals(PersistenceActivationMode.ACTIVE, evidence.mode());
        assertTrue(evidence.evidence().contains("legacy-import-source"));
        assertFalse(Files.exists(PersistenceFiles.replacementDatabase(
                targetDirectory)));
    }

    /** Regression: an uncertain historical source remains read-only. */
    @Test
    void uncertainHistoricalSourceIsReadOnly() throws Exception {
        Path targetDirectory = Files.createDirectory(
                temporaryDirectory.resolve("target"));
        Path legacyDirectory = Files.createDirectory(
                temporaryDirectory.resolve("legacy"));
        Files.writeString(legacyDirectory.resolve("tamework.sqlite"),
                "not sqlite");
        TameworkDataPathLayout layout = new TameworkDataPathLayout(
                targetDirectory, legacyDirectory, Optional.empty());

        TameworkPersistenceActivationEvidence evidence =
                new TameworkPersistenceActivationProbe(layout).probe();

        assertEquals(PersistenceActivationMode.READ_ONLY, evidence.mode());
        assertFalse(Files.exists(PersistenceFiles.replacementDatabase(
                targetDirectory)));
    }

    /** Regression: dormant and read-only authorities construct no runtime seams. */
    @Test
    void compositionGateRejectsDormantAndReadOnlyAuthorities() {
        TameworkRuntimeActivationPlanner planner =
                new TameworkRuntimeActivationPlanner(
                        TameworkRuntimeModuleCatalog.standard());
        TameworkRuntimeActivationPlan dormantPlan = planner.plan(
                TameworkActivationEvidence.empty());
        TameworkRuntimeActivationPlan activePlan = planner.plan(
                TameworkActivationEvidence.builder()
                        .content(TameworkRuntimeModule.GENERIC_PERSISTENCE)
                        .build());

        assertFalse(TameworkPersistenceActivationGate.shouldConstruct(
                dormantPlan,
                TameworkPersistenceActivationEvidence.dormant(false, false),
                TameworkRuntimeModule.GENERIC_PERSISTENCE
        ));
        assertTrue(TameworkPersistenceActivationGate.shouldConstruct(
                activePlan,
                TameworkPersistenceActivationEvidence.dormant(false, false),
                TameworkRuntimeModule.GENERIC_PERSISTENCE
        ));
        assertFalse(TameworkPersistenceActivationGate.shouldConstruct(
                activePlan,
                TameworkPersistenceActivationEvidence.readOnly(
                        true, "persistence-schema-unverified"),
                TameworkRuntimeModule.GENERIC_PERSISTENCE
        ));
    }

    private TameworkPersistenceActivationEvidence probe(Path database) {
        return new TameworkPersistenceActivationProbe(database).probe();
    }

    private void initializeSchema(Path database) {
        SqliteSchemaV2Manager manager = new SqliteSchemaV2Manager(
                new SqliteConnectionFactory(database));
        assertTrue(manager.initialize() instanceof PersistenceTransactionResult.Committed<?>);
    }

    private void initializeV1Schema(Path database) {
        SqliteSchemaV1Manager manager = new SqliteSchemaV1Manager(
                new SqliteConnectionFactory(database));
        assertTrue(manager.initialize() instanceof PersistenceTransactionResult.Committed<?>);
    }

    private void initializeReleasedRoutedV2Schema(Path database)
            throws Exception {
        initializeV1Schema(database);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_projection_outbox_type_sequence "
                    + "ON projection_outbox(event_type, event_sequence)");
            statement.execute("ALTER TABLE schema_history "
                    + "RENAME TO schema_history_v1_migration");
            statement.execute("""
                    CREATE TABLE schema_history (
                        version INTEGER PRIMARY KEY CHECK (version IN (1, 2)),
                        lineage TEXT NOT NULL CHECK (lineage = 'tamework-state'),
                        applied_at_ms INTEGER NOT NULL,
                        schema_hash TEXT NOT NULL CHECK (length(schema_hash) = 64)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO schema_history(
                        version, lineage, applied_at_ms, schema_hash
                    ) VALUES (2, 'tamework-state', -100, '"""
                    + SqliteReleasedRoutedV2Gateway.SCHEMA_HASH + "')");
            statement.execute("DROP TABLE schema_history_v1_migration");
        }
    }

    private int queryInt(Path database, String sql) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private void insertPublishedProjection(Path database) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO operation_envelope(
                        operation_id, idempotency_key, operation_kind,
                        payload_version, payload_json, phase, feature_scope,
                        created_at_ms, updated_at_ms
                    ) VALUES ('operation-projection', 'projection-key',
                        'projection-test', 1, '{}', 'PUBLISHED', 'core_identity', 1, 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO projection_outbox(
                        operation_id, event_type, aggregate_id,
                        aggregate_revision, payload_version, payload_json,
                        created_at_ms
                    ) VALUES ('operation-projection', 'PROFILE_CHANGED',
                        'profile-projection', 1, 1, '{}', 1)
                    """);
        }
    }

    private void acknowledgeAllProjectionConsumers(Path database)
            throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO projection_checkpoint(
                         consumer_id, acknowledged_sequence, updated_at_ms
                     ) VALUES (?, 1, 1)
                     """)) {
            LinkedHashSet<String> consumers = new LinkedHashSet<>();
            PublicPersistenceFeatureRegistry.create().descriptors().forEach(
                    descriptor -> descriptor.projectionConsumers().forEach(
                            consumer -> consumers.add(consumer.value())));
            for (String consumer : consumers) {
                statement.setString(1, consumer);
                statement.executeUpdate();
            }
        }
    }
}

package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageState;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceIntegrityService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageRecoveryProbeTest {
    @TempDir
    Path tempDir;

    @Test
    void realProbeIntegrityAndIndexPublicationMustAllPassBeforeWritesReopen() throws Exception {
        try (Harness harness = new Harness("recover.sqlite", true)) {
            AtomicInteger publications = new AtomicInteger();
            harness.storage.enterReadOnly("connection_outage", "incident-1");
            StorageRecoveryProbe probe = harness.probe(publications::incrementAndGet);

            StorageRecoveryProbe.ProbeResult result = probe.probe();

            assertTrue(result.recovered());
            assertEquals(1L, result.probeRevision());
            assertEquals(1, publications.get());
            assertEquals(PersistenceStorageState.HEALTHY, harness.storage.getState().status());
        }
    }

    @Test
    void missingSchemaRetainsReadOnlyWithoutPublishingIndexes() throws Exception {
        try (Harness harness = new Harness("missing-schema.sqlite", false)) {
            AtomicInteger publications = new AtomicInteger();
            harness.storage.enterReadOnly("bootstrap_failed", "incident-2");

            StorageRecoveryProbe.ProbeResult result =
                    harness.probe(publications::incrementAndGet).probe();

            assertEquals(StorageRecoveryProbe.ProbeStatus.RETAINED_READ_ONLY, result.status());
            assertEquals(0, publications.get());
            assertEquals(PersistenceStorageState.READ_ONLY, harness.storage.getState().status());
        }
    }

    @Test
    void v7SchemaRetainsReadOnlyUntilV8MigrationIsApplied() throws Exception {
        try (Harness harness = new Harness(
                "v7-schema.sqlite", SqliteSchemaMigrator.SCHEMA_VERSION_V7)) {
            AtomicInteger publications = new AtomicInteger();
            harness.storage.enterReadOnly("migration_incomplete", "incident-v8");

            StorageRecoveryProbe.ProbeResult result =
                    harness.probe(publications::incrementAndGet).probe();

            assertEquals(StorageRecoveryProbe.ProbeStatus.RETAINED_READ_ONLY, result.status());
            assertEquals(0, publications.get());
            assertNotNull(result.failure());
            assertEquals("schema_v8_unavailable", result.failure().getMessage());
            assertEquals(PersistenceStorageState.READ_ONLY, harness.storage.getState().status());
        }
    }

    @Test
    void publicationFailureReturnsToReadOnlyAfterDurableProbe() throws Exception {
        try (Harness harness = new Harness("publication-failure.sqlite", true)) {
            harness.storage.enterReadOnly("runtime_index_failed", "incident-3");

            StorageRecoveryProbe.ProbeResult result = harness.probe(
                    () -> { throw new IllegalStateException("index publication failed"); }).probe();

            assertEquals(StorageRecoveryProbe.ProbeStatus.RETAINED_READ_ONLY, result.status());
            assertEquals(PersistenceStorageState.READ_ONLY, harness.storage.getState().status());
        }
    }

    private final class Harness implements AutoCloseable {
        private final SqliteConnectionManager connections;
        private final SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        private final PersistenceStorageHealthService storage = new PersistenceStorageHealthService();
        private final PersistenceWriteQueue queue;
        private final PersistenceQuarantineRepository quarantineRepository;
        private final PersistenceQuarantineRegistry quarantines = new PersistenceQuarantineRegistry();
        private final PersistenceFeatureCircuitRepository circuitRepository;
        private final PersistenceFeatureCircuitRegistry circuits = new PersistenceFeatureCircuitRegistry();

        private Harness(String filename, boolean migrate) throws Exception {
            this(filename, migrate ? SqliteSchemaMigrator.SCHEMA_VERSION_V8 : 0);
        }

        private Harness(String filename, int targetSchemaVersion) throws Exception {
            connections = new SqliteConnectionManager(tempDir.resolve(filename));
            if (targetSchemaVersion > 0) {
                try (Connection connection = connections.openConnection()) {
                    migrator.migrate(connection);
                    if (targetSchemaVersion < SqliteSchemaMigrator.SCHEMA_VERSION_V8) {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "DELETE FROM schema_migrations WHERE version > ?")) {
                            statement.setInt(1, targetSchemaVersion);
                            statement.executeUpdate();
                        }
                    }
                }
            }
            queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(storage), null);
            quarantineRepository = new PersistenceQuarantineRepository(connections);
            circuitRepository = new PersistenceFeatureCircuitRepository(connections, queue);
        }

        private StorageRecoveryProbe probe(StorageRecoveryIndexPublisher publisher) {
            return new StorageRecoveryProbe(
                    "boot-test", connections, migrator, new PersistenceIntegrityService(connections),
                    storage, quarantineRepository, quarantines, circuitRepository, circuits,
                    List.of(publisher));
        }

        @Override
        public void close() {
            queue.close();
        }
    }
}

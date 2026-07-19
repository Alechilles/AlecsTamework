package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageRecoveryCoordinatorTest {
    @TempDir
    Path tempDir;

    @Test
    void operatorRequestRunsVerifiedProbeImmediately() throws Exception {
        try (Harness harness = new Harness("operator.sqlite")) {
            harness.storage.enterReadOnly("connection_outage", "incident-a");

            StorageRecoveryProbe.ProbeResult result = harness.coordinator.requestNow()
                    .get(5, TimeUnit.SECONDS);

            assertTrue(result.recovered());
            assertEquals(1L, harness.coordinator.attempts());
        }
    }

    @Test
    void integrityClassificationNeverSchedulesAutomaticRepair() throws Exception {
        try (Harness harness = new Harness("integrity.sqlite")) {
            harness.storage.enterReadOnly("sqlite_integrity_failed", "incident-b");
            harness.coordinator.start();

            Thread.sleep(StorageRecoveryCoordinator.INITIAL_DELAY_MS + 150L);

            assertEquals(0L, harness.coordinator.attempts());
        }
    }

    private final class Harness implements AutoCloseable {
        private final SqliteConnectionManager connections;
        private final PersistenceStorageHealthService storage = new PersistenceStorageHealthService();
        private final PersistenceWriteQueue queue;
        private final StorageRecoveryCoordinator coordinator;

        private Harness(String filename) throws Exception {
            connections = new SqliteConnectionManager(tempDir.resolve(filename));
            SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
            try (Connection connection = connections.openConnection()) {
                migrator.migrate(connection);
            }
            queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(storage), null);
            PersistenceQuarantineRepository quarantineRepository =
                    new PersistenceQuarantineRepository(connections);
            PersistenceQuarantineRegistry quarantines = new PersistenceQuarantineRegistry();
            PersistenceFeatureCircuitRepository circuitRepository =
                    new PersistenceFeatureCircuitRepository(connections, queue);
            PersistenceFeatureCircuitRegistry circuits = new PersistenceFeatureCircuitRegistry();
            StorageRecoveryProbe probe = new StorageRecoveryProbe(
                    "boot-test", connections, migrator, new PersistenceIntegrityService(connections),
                    storage, quarantineRepository, quarantines, circuitRepository, circuits, List.of());
            coordinator = new StorageRecoveryCoordinator(storage, probe);
        }

        @Override
        public void close() {
            coordinator.close();
            queue.close();
        }
    }
}

package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceFeatureCircuitRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void committedOverrideSurvivesRegistryReloadAndCannotEnablePastGlobalDisable() throws Exception {
        SqliteConnectionManager connections = migratedDatabase();
        try (PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null)) {
            PersistenceFeatureCircuitRepository repository =
                    new PersistenceFeatureCircuitRepository(connections, queue);
            PersistenceFeatureCircuitRegistry registry = new PersistenceFeatureCircuitRegistry();

            assertTrue(repository.set(PersistenceDomain.MANAGED_COOP_AUTOMATION, false,
                            "operator_pause", 10L, "console", registry)
                    .completion().get(2, TimeUnit.SECONDS).isCommitted());
            assertFalse(registry.isEnabled(PersistenceDomain.MANAGED_COOP_AUTOMATION));

            PersistenceFeatureCircuitRegistry restarted = new PersistenceFeatureCircuitRegistry();
            restarted.reload(repository.load());
            assertFalse(restarted.isEnabled(PersistenceDomain.MANAGED_COOP_AUTOMATION));
            assertTrue(restarted.isEnabled(PersistenceDomain.BREEDING_PAIRING));

            assertTrue(repository.set(PersistenceDomain.ALL_PERSISTENCE, false,
                            "emergency_pause", 11L, "console", restarted)
                    .completion().get(2, TimeUnit.SECONDS).isCommitted());
            assertFalse(restarted.isEnabled(PersistenceDomain.BREEDING_PAIRING));
        }
    }

    private SqliteConnectionManager migratedDatabase() throws Exception {
        SqliteConnectionManager connections =
                new SqliteConnectionManager(tempDir.resolve("circuits.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        return connections;
    }
}

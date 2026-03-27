package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PersistenceWriteQueueTest {
    @TempDir
    Path tempDir;

    @Test
    void marksHealthDegradedWhenWriteFails() throws Exception {
        Path sqlitePath = tempDir.resolve("test.sqlite");
        PersistenceHealthService healthService = new PersistenceHealthService();
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(sqlitePath);
        try (PersistenceWriteQueue queue = new PersistenceWriteQueue(connectionManager, healthService, null)) {
            queue.submit("intentional_failure", connection -> {
                throw new IllegalStateException("boom");
            });
            Thread.sleep(200L);
        }
        assertFalse(healthService.isHealthy());
    }
}

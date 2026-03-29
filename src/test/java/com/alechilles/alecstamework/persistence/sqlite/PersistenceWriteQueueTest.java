package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void awaitIdleWaitsForQueuedWorkToFinish() throws Exception {
        Path sqlitePath = tempDir.resolve("await-idle.sqlite");
        PersistenceHealthService healthService = new PersistenceHealthService();
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(sqlitePath);
        try (PersistenceWriteQueue queue = new PersistenceWriteQueue(connectionManager, healthService, null)) {
            assertTrue(queue.submit("slow_write", connection -> Thread.sleep(150L)));
            assertTrue(queue.awaitIdle(2_000L));
        }
    }
}

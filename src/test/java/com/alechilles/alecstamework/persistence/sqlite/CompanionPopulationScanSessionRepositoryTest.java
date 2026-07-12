package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationScanSessionRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void activeEpochResumesAcrossRestartAndRotatesOnlyAfterReady() throws Exception {
        Path database = tempDir.resolve("restart.sqlite");
        CompanionPopulationScanSessionRepository.Session first;
        try (Harness harness = harness(database)) {
            first = acquire(harness.repository());
            assertEquals(CompanionPopulationScanSessionRepository.State.ACTIVE, first.state());
        }

        try (Harness restarted = harness(database)) {
            CompanionPopulationScanSessionRepository.Session resumed = acquire(restarted.repository());
            assertEquals(first.epoch(), resumed.epoch());
            assertTrue(markReady(restarted.repository(), resumed.epoch()));
        }

        try (Harness freshCycle = harness(database)) {
            CompanionPopulationScanSessionRepository.Session rotated = acquire(freshCycle.repository());
            assertNotEquals(first.epoch(), rotated.epoch());
            assertEquals(CompanionPopulationScanSessionRepository.State.ACTIVE, rotated.state());
        }
    }

    @Test
    void staleReadyCannotCompleteTheCurrentSession() throws Exception {
        try (Harness harness = harness(tempDir.resolve("stale.sqlite"))) {
            CompanionPopulationScanSessionRepository.Session first = acquire(harness.repository());
            assertTrue(markReady(harness.repository(), first.epoch()));
            CompanionPopulationScanSessionRepository.Session current = acquire(harness.repository());

            assertFalse(markReady(harness.repository(), first.epoch()));
            assertEquals(current, harness.repository().loadCurrent());
        }
    }

    private Harness harness(Path database) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(database);
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections,
                new PersistenceHealthService(),
                null
        );
        return new Harness(
                queue,
                new CompanionPopulationScanSessionRepository(connections, queue)
        );
    }

    private static CompanionPopulationScanSessionRepository.Session acquire(
            CompanionPopulationScanSessionRepository repository
    ) throws Exception {
        PersistenceWriteQueue.WriteOutcome<CompanionPopulationScanSessionRepository.Session> outcome =
                repository.acquireOrResumeAsync().completion().get(2, TimeUnit.SECONDS);
        assertTrue(outcome.isCommitted());
        return outcome.value();
    }

    private static boolean markReady(
            CompanionPopulationScanSessionRepository repository,
            String epoch
    ) throws Exception {
        PersistenceWriteQueue.WriteOutcome<Boolean> outcome = repository.markReadyAsync(epoch)
                .completion()
                .get(2, TimeUnit.SECONDS);
        assertTrue(outcome.isCommitted());
        return Boolean.TRUE.equals(outcome.value());
    }

    private record Harness(
            PersistenceWriteQueue queue,
            CompanionPopulationScanSessionRepository repository
    ) implements AutoCloseable {
        @Override
        public void close() {
            queue.close();
        }
    }
}

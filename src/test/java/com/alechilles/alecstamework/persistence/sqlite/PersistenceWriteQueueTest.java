package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceWriteQueueTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsTerminalWriteFailureWithoutTimingSleeps() throws Exception {
        Path sqlitePath = tempDir.resolve("test.sqlite");
        PersistenceHealthService healthService = new PersistenceHealthService();
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(sqlitePath);
        PersistenceWriteQueue.WriteResult result;
        try (PersistenceWriteQueue queue = new PersistenceWriteQueue(connectionManager, healthService, null)) {
            result = queue.submitWithCompletion("intentional_failure", connection -> {
                throw new IllegalStateException("boom");
            }).get(2, TimeUnit.SECONDS);
        }
        assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, result.status());
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

    @Test
    void closeDrainsAcceptedWriteAndRunsAfterCommitExactlyOnce() throws Exception {
        Path sqlitePath = tempDir.resolve("drain.sqlite");
        PersistenceHealthService healthService = new PersistenceHealthService();
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(sqlitePath);
        AtomicInteger callbacks = new AtomicInteger();
        PersistenceWriteQueue queue = new PersistenceWriteQueue(connectionManager, healthService, null);
        CompletableFuture<PersistenceWriteQueue.WriteResult> completion = queue.submitWithCompletion(
                "drained_insert",
                connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE drained (value INTEGER NOT NULL)");
                        statement.execute("INSERT INTO drained (value) VALUES (7)");
                    }
                },
                callbacks::incrementAndGet
        );

        queue.close();

        assertTrue(completion.get(1, TimeUnit.SECONDS).isCommitted());
        assertEquals(1, callbacks.get());
        assertEquals(PersistenceWriteQueue.QueueState.CLOSED, queue.getState());
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT value FROM drained")) {
            assertTrue(resultSet.next());
            assertEquals(7, resultSet.getInt(1));
        }
    }

    @Test
    void submissionAfterDrainBeginsIsRejected() throws Exception {
        Path sqlitePath = tempDir.resolve("reject-draining.sqlite");
        PersistenceHealthService healthService = new PersistenceHealthService();
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(sqlitePath);
        PersistenceWriteQueue queue = new PersistenceWriteQueue(connectionManager, healthService, null);
        CountDownLatch transactionStarted = new CountDownLatch(1);
        CountDownLatch releaseTransaction = new CountDownLatch(1);
        CompletableFuture<PersistenceWriteQueue.WriteResult> accepted = queue.submitWithCompletion(
                "held_write",
                connection -> {
                    transactionStarted.countDown();
                    assertTrue(releaseTransaction.await(2, TimeUnit.SECONDS));
                }
        );
        assertTrue(transactionStarted.await(1, TimeUnit.SECONDS));

        Thread closer = new Thread(queue::close, "persistence-close-test");
        closer.start();
        awaitState(queue, PersistenceWriteQueue.QueueState.DRAINING);
        PersistenceWriteQueue.WriteResult rejected = queue.submitWithCompletion(
                "late_write",
                connection -> {
                }
        ).get(1, TimeUnit.SECONDS);
        releaseTransaction.countDown();
        closer.join(2_000L);

        assertEquals(PersistenceWriteQueue.WriteStatus.REJECTED, rejected.status());
        assertTrue(accepted.get(1, TimeUnit.SECONDS).isCommitted());
        assertFalse(closer.isAlive());
    }

    private static void awaitState(PersistenceWriteQueue queue,
                                   PersistenceWriteQueue.QueueState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (System.nanoTime() < deadline && queue.getState() != expected) {
            Thread.onSpinWait();
        }
        assertEquals(expected, queue.getState());
    }
}


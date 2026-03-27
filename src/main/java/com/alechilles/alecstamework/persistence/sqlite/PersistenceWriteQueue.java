package com.alechilles.alecstamework.persistence.sqlite;

import com.hypixel.hytale.logger.HytaleLogger;
import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializes DB mutations on one worker thread and marks persistence unhealthy on write failures.
 */
public final class PersistenceWriteQueue implements AutoCloseable {
    @FunctionalInterface
    public interface SqlTransaction {
        void run(@Nonnull Connection connection) throws Exception;
    }

    private final SqliteConnectionManager connectionManager;
    private final PersistenceHealthService healthService;
    @Nullable
    private final HytaleLogger logger;
    private final ExecutorService executor;

    public PersistenceWriteQueue(@Nonnull SqliteConnectionManager connectionManager,
                                 @Nonnull PersistenceHealthService healthService,
                                 @Nullable HytaleLogger logger) {
        this.connectionManager = connectionManager;
        this.healthService = healthService;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "tamework-persistence-writer");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public boolean submit(@Nonnull String operationName, @Nonnull SqlTransaction transaction) {
        if (!healthService.isHealthy()) {
            return false;
        }
        executor.execute(() -> runTransaction(operationName, transaction));
        return true;
    }

    private void runTransaction(@Nonnull String operationName, @Nonnull SqlTransaction transaction) {
        try (Connection connection = connectionManager.openConnection()) {
            connection.setAutoCommit(false);
            try {
                transaction.run(connection);
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ex) {
            String reason = "sqlite_write_failed:" + operationName + ":" + ex.getClass().getSimpleName();
            healthService.markDegraded(reason);
            if (logger != null) {
                logger.at(Level.SEVERE).log("SQLite write failed for operation '" + operationName + "': " + ex.getMessage());
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

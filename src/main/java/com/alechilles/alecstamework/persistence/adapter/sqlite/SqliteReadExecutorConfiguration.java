package com.alechilles.alecstamework.persistence.adapter.sqlite;

/**
 * Bounded lane policy for replacement SQLite reads.
 *
 * @param gameplayThreads gameplay-critical read workers
 * @param gameplayQueueCapacity queued gameplay reads
 * @param diagnosticThreads diagnostic read workers
 * @param diagnosticQueueCapacity queued diagnostic reads
 * @param defaultShutdownTimeoutMs bounded graceful close duration
 */
public record SqliteReadExecutorConfiguration(int gameplayThreads,
                                              int gameplayQueueCapacity,
                                              int diagnosticThreads,
                                              int diagnosticQueueCapacity,
                                              long defaultShutdownTimeoutMs) {
    public static final SqliteReadExecutorConfiguration DEFAULT =
            new SqliteReadExecutorConfiguration(2, 256, 1, 32, 5_000);

    public SqliteReadExecutorConfiguration {
        if (gameplayThreads < 1 || gameplayQueueCapacity < 1
                || diagnosticThreads < 1 || diagnosticQueueCapacity < 1
                || defaultShutdownTimeoutMs < 1) {
            throw new IllegalArgumentException("SQLite read executor limits must be positive");
        }
    }
}

package com.alechilles.alecstamework.persistence.adapter.sqlite;

/**
 * Bounded resource and retry policy for the replacement single writer.
 *
 * @param queueCapacity maximum accepted operations waiting for the writer
 * @param maxBusyRetries maximum replays after a known rollback
 * @param busyRetryDelayMs delay between safe busy replays
 * @param defaultShutdownTimeoutMs default bounded drain duration
 */
public record SqliteWriterConfiguration(int queueCapacity,
                                        int maxBusyRetries,
                                        long busyRetryDelayMs,
                                        long defaultShutdownTimeoutMs) {
    public static final SqliteWriterConfiguration DEFAULT =
            new SqliteWriterConfiguration(1_024, 3, 10, 5_000);

    public SqliteWriterConfiguration {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("Writer queue capacity must be positive");
        }
        if (maxBusyRetries < 0 || busyRetryDelayMs < 0 || defaultShutdownTimeoutMs < 1) {
            throw new IllegalArgumentException("Writer retry and shutdown values are invalid");
        }
    }
}

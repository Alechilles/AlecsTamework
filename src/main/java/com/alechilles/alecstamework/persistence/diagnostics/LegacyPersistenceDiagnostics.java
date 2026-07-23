package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.nio.file.Path;
import javax.annotation.Nonnull;

/** Immutable diagnostics snapshot from the temporary legacy persistence runtime. */
public record LegacyPersistenceDiagnostics(
        @Nonnull Path databasePath,
        long sqliteBytes,
        long walBytes,
        long shmBytes,
        long totalBytes,
        @Nonnull PersistenceWriteQueue.QueueMetrics queueMetrics,
        @Nonnull PersistenceHealthService.HealthState healthState
) {
}

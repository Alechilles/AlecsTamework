package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.store.CompanionWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Persistence diagnostics over the companion index and its writer. Reads only memory, so it is
 * safe from any thread: the folder size comes from a cached supplier that never reads files on
 * the caller's thread.
 */
public final class IndexDiagnosticsApi implements DiagnosticsApi {
    private final CompanionIndex index;
    private final Supplier<CompanionWriter.Status> writerStatus;
    private final String folder;
    private final LongSupplier folderBytes;
    private final IntSupplier unreadableRecords;

    /**
     * @param folder      the companion folder, reported as {@code databasePath}
     * @param folderBytes the folder's cached size; must not block
     */
    public IndexDiagnosticsApi(@Nonnull CompanionIndex index,
                               @Nonnull Supplier<CompanionWriter.Status> writerStatus,
                               @Nonnull String folder,
                               @Nonnull LongSupplier folderBytes,
                               @Nonnull IntSupplier unreadableRecords) {
        this.index = Objects.requireNonNull(index, "index");
        this.writerStatus = Objects.requireNonNull(writerStatus, "writerStatus");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.folderBytes = Objects.requireNonNull(folderBytes, "folderBytes");
        this.unreadableRecords = Objects.requireNonNull(unreadableRecords, "unreadableRecords");
    }

    @Override
    @Nonnull
    public PersistenceDiagnosticsView getPersistenceDiagnostics() {
        CompanionWriter.Status writer = writerStatus.get();
        long[] counts = new long[LocationKind.values().length];
        index.forEach(record -> counts[record.location().kind().ordinal()]++);
        Map<String, Long> byLocation = new HashMap<>();
        for (LocationKind kind : LocationKind.values()) {
            byLocation.put(kind.name(), counts[kind.ordinal()]);
        }
        String failure = writer.lastFailure();
        return new PersistenceDiagnosticsView(
                folder,
                0L,
                0L,
                0L,
                folderBytes.getAsLong(),
                new PersistenceDiagnosticsView.QueueMetricsView(
                        writer.pendingOwners() + writer.pendingSnapshots(),
                        0, 0, 0L, 0L, 0L, 0L, 0.0, 0.0, 0.0, failure, 0L),
                new PersistenceDiagnosticsView.HealthView(failure == null ? "HEALTHY" : "DEGRADED", failure, 0L),
                byLocation,
                writer.lastFlushAtMs(),
                failure,
                unreadableRecords.getAsInt()
        );
    }
}

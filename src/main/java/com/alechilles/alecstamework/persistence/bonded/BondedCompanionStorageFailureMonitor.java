package com.alechilles.alecstamework.persistence.bonded;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Captures one bounded comparison between bonded storage startup and failure. */
public final class BondedCompanionStorageFailureMonitor {
    private final Path database;
    private final BondedCompanionSchemaManager schemas;
    @Nullable
    private final Consumer<BondedCompanionStorageFailureEvidence> sink;
    private final FileSnapshot baseline;
    private final AtomicBoolean recorded = new AtomicBoolean();

    public BondedCompanionStorageFailureMonitor(
            @Nonnull Path database,
            @Nonnull BondedCompanionSchemaManager schemas,
            @Nullable Consumer<BondedCompanionStorageFailureEvidence> sink
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.sink = sink;
        baseline = FileSnapshot.capture(database);
    }

    public boolean isStorageFailure(@Nonnull Throwable failure) {
        return sqlFailure(failure) != null;
    }

    @Nullable
    public BondedCompanionStorageFailureEvidence captureOnce(
            @Nonnull String operation,
            @Nonnull Throwable failure
    ) {
        if (!recorded.compareAndSet(false, true)) return null;
        FileSnapshot current = FileSnapshot.capture(database);
        BondedCompanionPersistenceReadiness schema = schemas.verify();
        SQLException sql = sqlFailure(failure);
        BondedCompanionStorageFailureEvidence evidence =
                new BondedCompanionStorageFailureEvidence(
                        operation,
                        sql == null ? failure.getClass().getSimpleName()
                                : sql.getClass().getSimpleName(),
                        failureReason(sql),
                        baseline.state(),
                        sizeBucket(baseline.size()),
                        current.state(),
                        sizeBucket(current.size()),
                        compare(baseline.fileKey(), current.fileKey()),
                        compare(baseline.size(), current.size()),
                        compare(baseline.modifiedAtMs(), current.modifiedAtMs()),
                        Files.exists(sidecar("-wal")),
                        Files.exists(sidecar("-shm")),
                        schema.availability().available() ? "ready" : "failed",
                        schema.diagnosticCode(),
                        sql == null ? -1 : sql.getErrorCode(),
                        sql == null || sql.getSQLState() == null
                                ? "unknown" : sql.getSQLState(),
                        failure
                );
        if (sink != null) {
            try {
                sink.accept(evidence);
            } catch (Throwable ignored) {
                // Telemetry and diagnostic consumers must never re-open the crash path.
            }
        }
        return evidence;
    }

    private Path sidecar(String suffix) {
        return database.resolveSibling(database.getFileName() + suffix);
    }

    @Nullable
    private static SQLException sqlFailure(Throwable failure) {
        Throwable cursor = Objects.requireNonNull(failure, "failure");
        for (int depth = 0; cursor != null && depth < 32; depth++) {
            if (cursor instanceof SQLException sql) return sql;
            cursor = cursor.getCause();
        }
        return null;
    }

    private static String sizeBucket(long size) {
        if (size < 0L) return "unavailable";
        if (size == 0L) return "0";
        if (size < 4L * 1024L) return "1-4k";
        if (size < 64L * 1024L) return "4-64k";
        if (size < 1024L * 1024L) return "64k-1m";
        return "1m+";
    }

    private static String failureReason(@Nullable SQLException failure) {
        if (failure == null || failure.getMessage() == null) return "unknown";
        String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("no such table")) return "missing_table";
        if (message.contains("database is locked")) return "locked";
        if (message.contains("database is busy")) return "busy";
        if (message.contains("disk i/o")) return "disk_io";
        if (message.contains("readonly") || message.contains("read-only")) {
            return "read_only";
        }
        if (message.contains("database or disk is full")) return "storage_full";
        if (message.contains("malformed") || message.contains("corrupt")) {
            return "corrupt";
        }
        if (message.contains("unable to open database")) return "cannot_open";
        if (message.contains("constraint")) return "constraint";
        return "unknown";
    }

    private static String compare(@Nullable Object before, @Nullable Object after) {
        if (before == null || after == null) return "unavailable";
        return Objects.equals(before, after) ? "same" : "changed";
    }

    private static String compare(long before, long after) {
        if (before < 0L || after < 0L) return "unavailable";
        if (before == after) return "same";
        return after < before ? "decreased" : "increased";
    }

    private record FileSnapshot(
            String state,
            @Nullable Object fileKey,
            long size,
            long modifiedAtMs
    ) {
        private static FileSnapshot capture(Path path) {
            try {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    return new FileSnapshot("missing", null, -1L, -1L);
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) {
                    return new FileSnapshot("not_regular", attributes.fileKey(),
                            -1L, attributes.lastModifiedTime().toMillis());
                }
                String state = attributes.size() == 0L ? "empty" : "present";
                return new FileSnapshot(state, attributes.fileKey(),
                        attributes.size(), attributes.lastModifiedTime().toMillis());
            } catch (Exception failure) {
                return new FileSnapshot("unreadable", null, -1L, -1L);
            }
        }
    }
}

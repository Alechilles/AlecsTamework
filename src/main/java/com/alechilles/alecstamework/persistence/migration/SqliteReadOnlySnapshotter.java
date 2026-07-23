package com.alechilles.alecstamework.persistence.migration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Copies one consistent SQLite view without opening the source database.
 *
 * <p>The source main file and WAL are copied only when their before/after fingerprints are stable.
 * SQLite then consolidates the owned staging copy. This avoids the source {@code -shm} writes that
 * even a nominally read-only SQLite connection can perform.</p>
 */
public final class SqliteReadOnlySnapshotter {
    private static final int COPY_ATTEMPTS = 3;

    /** Creates a temporary backup owned by the returned closeable snapshot. */
    @Nonnull
    public Snapshot create(@Nonnull Path sourcePath, @Nonnull Path snapshotDirectory)
            throws Exception {
        if (sourcePath == null || snapshotDirectory == null) {
            throw new IllegalArgumentException("Source and snapshot directory are required");
        }
        Path source = sourcePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("SQLite source does not exist: " + source);
        }
        Path workspace = snapshotDirectory.toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Path stagingDirectory = Files.createTempDirectory(workspace, "tamework-source-stage-");
        Path stagingDatabase = stagingDirectory.resolve("source.sqlite");
        Path snapshot = Files.createTempFile(workspace, "tamework-source-", ".sqlite");
        Files.delete(snapshot);
        try {
            stableCopy(source, stagingDatabase);
            backup(stagingDatabase, snapshot);
            return new Snapshot(
                    snapshot,
                    new LegacySourceFingerprint(
                            sha256(snapshot),
                            Files.size(source),
                            Files.getLastModifiedTime(source).toMillis()
                    )
            );
        } catch (Exception failure) {
            deleteSnapshotFiles(snapshot, failure);
            throw failure;
        } finally {
            deleteStagingFiles(stagingDirectory, stagingDatabase);
        }
    }

    private void stableCopy(Path source, Path stagingDatabase) throws Exception {
        for (int attempt = 1; attempt <= COPY_ATTEMPTS; attempt++) {
            SourceEvidence before = sourceEvidence(source);
            copySourceArtifact(source, stagingDatabase);
            if (before.wal().isPresent()) {
                copySourceArtifact(walPath(source), walPath(stagingDatabase));
            }
            SourceEvidence after = sourceEvidence(source);
            if (before.equals(after)
                    && before.main().sha256().equals(sha256(stagingDatabase))
                    && copiedWalMatches(before.wal(), stagingDatabase)) {
                return;
            }
            deleteStagingArtifacts(stagingDatabase);
        }
        throw new IllegalStateException("sqlite_source_changed_during_snapshot");
    }

    private void copySourceArtifact(Path source, Path destination) throws Exception {
        Files.copy(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
        );
    }

    private boolean copiedWalMatches(Optional<FileEvidence> sourceWal, Path stagingDatabase)
            throws Exception {
        Path stagedWal = walPath(stagingDatabase);
        return sourceWal.isEmpty()
                ? !Files.exists(stagedWal)
                : Files.isRegularFile(stagedWal)
                && sourceWal.orElseThrow().sha256().equals(sha256(stagedWal));
    }

    private SourceEvidence sourceEvidence(Path source) throws Exception {
        return new SourceEvidence(
                evidence(source),
                Files.isRegularFile(walPath(source))
                        ? Optional.of(evidence(walPath(source)))
                        : Optional.empty()
        );
    }

    private FileEvidence evidence(Path path) throws Exception {
        return new FileEvidence(
                Files.size(path),
                Files.getLastModifiedTime(path).toMillis(),
                sha256(path)
        );
    }

    private void backup(Path stagedSource, Path snapshot) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String sourceUrl = "jdbc:sqlite:" + stagedSource;
        try (Connection connection = DriverManager.getConnection(sourceUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            String escapedDestination = snapshot.toString().replace("'", "''");
            statement.execute("BACKUP main TO '" + escapedDestination + "'");
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteSnapshotFiles(Path snapshot, Throwable original) {
        for (Path path : snapshotFiles(snapshot)) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void deleteStagingFiles(Path directory, Path database) {
        deleteStagingArtifacts(database);
        try {
            Files.deleteIfExists(directory);
        } catch (Exception ignored) {
            // The caller's source and completed snapshot are independent of staging cleanup.
        }
    }

    private static void deleteStagingArtifacts(Path database) {
        for (Path path : snapshotFiles(database)) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
                // A subsequent attempt overwrites known files; final cleanup is best effort.
            }
        }
    }

    private static Path walPath(Path database) {
        return database.resolveSibling(database.getFileName() + "-wal");
    }

    private static Path[] snapshotFiles(Path snapshot) {
        return new Path[]{
                snapshot,
                snapshot.resolveSibling(snapshot.getFileName() + "-wal"),
                snapshot.resolveSibling(snapshot.getFileName() + "-shm")
        };
    }

    /** Temporary consistent database snapshot; closing it removes only its owned files. */
    public record Snapshot(@Nonnull Path path, @Nonnull LegacySourceFingerprint fingerprint)
            implements AutoCloseable {
        public Snapshot {
            if (path == null || fingerprint == null) {
                throw new IllegalArgumentException("Snapshot path and fingerprint are required");
            }
        }

        @Override
        public void close() throws Exception {
            Exception failure = null;
            for (Path ownedPath : snapshotFiles(path)) {
                try {
                    Files.deleteIfExists(ownedPath);
                } catch (Exception cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record SourceEvidence(
            FileEvidence main,
            Optional<FileEvidence> wal
    ) {
        private SourceEvidence {
            Objects.requireNonNull(main);
            Objects.requireNonNull(wal);
        }
    }

    private record FileEvidence(long size, long modifiedAtMs, String sha256) {
    }
}

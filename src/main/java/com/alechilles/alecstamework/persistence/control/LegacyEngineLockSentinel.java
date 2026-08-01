package com.alechilles.alecstamework.persistence.control;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Retires the former regular-file engine lock without allowing backups to
 * encounter a process-held legacy lock.
 */
final class LegacyEngineLockSentinel {
    static final String LEGACY_LOCK_FILENAME =
            ".tamework-persistence-engine.lock";

    private LegacyEngineLockSentinel() {
    }

    static void prepare(Path dataDirectory) throws IOException {
        Path legacyPath = dataDirectory.resolve(LEGACY_LOCK_FILENAME);
        if (Files.notExists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            createDirectorySentinel(legacyPath);
            return;
        }
        if (Files.isDirectory(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("persistence_engine_legacy_lock_path_invalid");
        }
        retireRegularFile(legacyPath);
    }

    private static void retireRegularFile(Path legacyPath) throws IOException {
        try (FileChannel channel = FileChannel.open(
                legacyPath,
                StandardOpenOption.WRITE
        )) {
            FileLock lock = tryLock(channel);
            try {
                // Confirm the legacy path was not held before retiring it.
            } finally {
                lock.release();
            }
        }
        Files.delete(legacyPath);
        createDirectorySentinel(legacyPath);
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw unavailable();
            }
            return lock;
        } catch (OverlappingFileLockException failure) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("persistence_engine_lock_unavailable");
    }

    private static void createDirectorySentinel(Path legacyPath)
            throws IOException {
        try {
            Files.createDirectory(legacyPath);
        } catch (FileAlreadyExistsException failure) {
            if (!Files.isDirectory(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
                throw failure;
            }
        }
    }
}

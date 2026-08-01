package com.alechilles.alecstamework.persistence.migration;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.annotation.Nonnull;

/** Process-level admission lock that serializes replacement import publication. */
final class ImportAdmissionLock implements AutoCloseable {
    static final String LOCK_DIRECTORY = ".tamework-import-lock";
    static final String LOCK_FILENAME = "LOCK";

    private final FileChannel channel;
    private final FileLock lock;

    private ImportAdmissionLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    @Nonnull
    static ImportAdmissionLock acquire(@Nonnull Path targetDirectory) throws Exception {
        if (targetDirectory == null) {
            throw new IllegalArgumentException("Target directory is required");
        }
        java.nio.file.Files.createDirectories(targetDirectory);
        Path lockDirectory = targetDirectory.resolve(LOCK_DIRECTORY);
        java.nio.file.Files.createDirectories(lockDirectory);
        Path lockPath = lockDirectory.resolve(LOCK_FILENAME);
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IllegalStateException("persistence_import_lock_unavailable");
            }
            return new ImportAdmissionLock(channel, lock);
        } catch (OverlappingFileLockException failure) {
            channel.close();
            throw new IllegalStateException("persistence_import_lock_unavailable", failure);
        } catch (Exception failure) {
            channel.close();
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        try {
            lock.release();
        } catch (Exception releaseFailure) {
            failure = releaseFailure;
        }
        try {
            channel.close();
        } catch (Exception closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

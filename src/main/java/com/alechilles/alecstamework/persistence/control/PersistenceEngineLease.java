package com.alechilles.alecstamework.persistence.control;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Held process lease proving that exactly one persistence engine owns a directory.
 *
 * <p>Replacement acquisition does not change a legacy manifest. Only
 * {@link #publishStartupComplete()} performs the irreversible lineage cutover.</p>
 */
public final class PersistenceEngineLease implements AutoCloseable {
    public static final String LOCK_FILENAME =
            ".tamework-persistence-engine.lock";

    private final PersistenceEngineLineage requestedLineage;
    private final PersistenceEngineManifestStore manifests;
    private final LongSupplier clock;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean startupPublished;

    private PersistenceEngineLease(
            PersistenceEngineLineage requestedLineage,
            PersistenceEngineManifestStore manifests,
            LongSupplier clock,
            FileChannel channel,
            FileLock lock
    ) {
        this.requestedLineage = requestedLineage;
        this.manifests = manifests;
        this.clock = clock;
        this.channel = channel;
        this.lock = lock;
    }

    /** Acquires the public v2-v4 engine and refuses a published replacement. */
    @Nonnull
    public static PersistenceEngineLease acquireLegacy(
            @Nonnull Path dataDirectory
    ) {
        return acquire(
                dataDirectory,
                PersistenceEngineLineage.LEGACY_PUBLIC,
                System::currentTimeMillis
        );
    }

    /** Acquires replacement startup without changing the selected lineage. */
    @Nonnull
    public static PersistenceEngineLease acquireReplacement(
            @Nonnull Path dataDirectory
    ) {
        return acquire(
                dataDirectory,
                PersistenceEngineLineage.REPLACEMENT,
                System::currentTimeMillis
        );
    }

    static PersistenceEngineLease acquire(
            Path dataDirectory,
            PersistenceEngineLineage requested,
            LongSupplier clock
    ) {
        if (dataDirectory == null || requested == null || clock == null) {
            throw new IllegalArgumentException(
                    "Engine lease directory, lineage, and clock are required"
            );
        }
        Path directory = dataDirectory.toAbsolutePath().normalize();
        FileChannel channel = null;
        try {
            java.nio.file.Files.createDirectories(directory);
            channel = FileChannel.open(
                    directory.resolve(LOCK_FILENAME),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IllegalStateException(
                        "persistence_engine_lock_unavailable"
                );
            }
            PersistenceEngineManifestStore manifests =
                    new PersistenceEngineManifestStore(directory);
            Optional<PersistenceEngineManifest> current = manifests.read();
            validateSelection(requested, current);
            PersistenceEngineLease lease = new PersistenceEngineLease(
                    requested,
                    manifests,
                    clock,
                    channel,
                    lock
            );
            if (requested == PersistenceEngineLineage.LEGACY_PUBLIC) {
                lease.writeManifest(false, false);
            }
            return lease;
        } catch (OverlappingFileLockException failure) {
            closeChannel(channel);
            throw new IllegalStateException(
                    "persistence_engine_lock_unavailable",
                    failure
            );
        } catch (RuntimeException failure) {
            closeChannel(channel);
            throw failure;
        } catch (Exception failure) {
            closeChannel(channel);
            throw new IllegalStateException(
                    "persistence_engine_lease_failed",
                    failure
            );
        }
    }

    /** Atomically publishes successful startup and replacement cutover. */
    public synchronized void publishStartupComplete() {
        requireOpen();
        if (startupPublished) {
            return;
        }
        try {
            writeManifest(true, false);
            startupPublished = true;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "persistence_engine_manifest_publish_failed",
                    failure
            );
        }
    }

    /** Reads current durable selection while this process owns the lock. */
    @Nonnull
    public synchronized Optional<PersistenceEngineManifest> manifest() {
        requireOpen();
        try {
            return manifests.read();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "persistence_engine_manifest_read_failed",
                    failure
            );
        }
    }

    @Nonnull
    public PersistenceEngineLineage requestedLineage() {
        return requestedLineage;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        if (startupPublished) {
            try {
                writeManifest(true, true);
            } catch (Exception manifestFailure) {
                failure = new IllegalStateException(
                        "persistence_engine_clean_shutdown_publish_failed",
                        manifestFailure
                );
            }
        }
        try {
            lock.release();
        } catch (Exception releaseFailure) {
            failure = merge(failure, releaseFailure);
        }
        try {
            channel.close();
        } catch (Exception closeFailure) {
            failure = merge(failure, closeFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void writeManifest(
            boolean startupComplete,
            boolean cleanShutdown
    ) throws Exception {
        manifests.write(new PersistenceEngineManifest(
                PersistenceEngineManifest.CURRENT_FORMAT,
                requestedLineage,
                startupComplete,
                cleanShutdown,
                clock.getAsLong()
        ));
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "persistence_engine_lease_closed"
            );
        }
    }

    private static void validateSelection(
            PersistenceEngineLineage requested,
            Optional<PersistenceEngineManifest> current
    ) {
        if (requested == PersistenceEngineLineage.LEGACY_PUBLIC
                && current.map(PersistenceEngineManifest::lineage)
                .filter(PersistenceEngineLineage.REPLACEMENT::equals)
                .isPresent()) {
            throw new IllegalStateException(
                    "replacement_persistence_lineage_already_selected"
            );
        }
    }

    private static RuntimeException merge(
            RuntimeException existing,
            Exception next
    ) {
        if (existing == null) {
            return new IllegalStateException(
                    "persistence_engine_lease_close_failed",
                    next
            );
        }
        existing.addSuppressed(next);
        return existing;
    }

    private static void closeChannel(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception ignored) {
            // Preserve the acquisition failure as the primary diagnostic.
        }
    }
}

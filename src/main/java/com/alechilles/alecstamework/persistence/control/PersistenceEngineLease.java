package com.alechilles.alecstamework.persistence.control;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
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
            "LOCK";
    private static final long LOCK_HANDOFF_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(3);
    private static final long LOCK_RETRY_INTERVAL_NANOS =
            TimeUnit.MILLISECONDS.toNanos(50);
    private static final String JVM_RESERVATION_PREFIX =
            "com.alechilles.alecstamework.persistence.owner:";

    private final JvmReservation reservation;
    private final PersistenceEngineLineage requestedLineage;
    private final PersistenceEngineManifestStore manifests;
    private final LongSupplier clock;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean startupPublished;

    private PersistenceEngineLease(
            JvmReservation reservation,
            PersistenceEngineLineage requestedLineage,
            PersistenceEngineManifestStore manifests,
            LongSupplier clock,
            FileChannel channel,
            FileLock lock
    ) {
        this.reservation = reservation;
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
        Path directory = canonicalDirectory(dataDirectory);
        long deadline = System.nanoTime() + LOCK_HANDOFF_TIMEOUT_NANOS;
        JvmReservation reservation = JvmReservation.acquire(
                directory,
                deadline
        );
        try {
            PersistenceEngineLockUnavailableException lastFailure = null;
            while (true) {
                if (lastFailure != null
                        && (System.nanoTime() >= deadline
                        || Thread.currentThread().isInterrupted())) {
                    throw lastFailure;
                }
                try {
                    return acquireOnce(
                            directory,
                            requested,
                            clock,
                            reservation
                    );
                } catch (PersistenceEngineLockUnavailableException failure) {
                    if (failure.sameProcess()) {
                        throw failure;
                    }
                    lastFailure = failure;
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0
                            || Thread.currentThread().isInterrupted()) {
                        throw failure;
                    }
                    LockSupport.parkNanos(Math.min(
                            remaining,
                            LOCK_RETRY_INTERVAL_NANOS
                    ));
                }
            }
        } catch (PersistenceEngineLockUnavailableException failure) {
            if (failure.sameProcess()) {
                reservation.retain();
            }
            reservation.close();
            throw failure;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    private static PersistenceEngineLease acquireOnce(
            Path directory,
            PersistenceEngineLineage requested,
            LongSupplier clock,
            JvmReservation reservation
    ) {
        FileChannel channel = null;
        try {
            Files.createDirectories(directory);
            LegacyEngineLockSentinel.prepare(directory);
            channel = openLock(directory.resolve(LOCK_FILENAME));
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw PersistenceEngineLockUnavailableException.active(
                        false,
                        null
                );
            }
            PersistenceEngineManifestStore manifests =
                    new PersistenceEngineManifestStore(directory);
            Optional<PersistenceEngineManifest> current = manifests.read();
            validateSelection(requested, current);
            PersistenceEngineLease lease = new PersistenceEngineLease(
                    reservation,
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
            retainOverlappingChannel(channel);
            throw PersistenceEngineLockUnavailableException.active(
                    true,
                    failure
            );
        } catch (RuntimeException failure) {
            if (!closeChannel(channel)) {
                reservation.retain();
            }
            throw failure;
        } catch (Error failure) {
            if (!closeChannel(channel)) {
                reservation.retain();
            }
            throw failure;
        } catch (Exception failure) {
            if (!closeChannel(channel)) {
                reservation.retain();
            }
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
        closeLease(true);
    }

    /**
     * Releases ownership without claiming that the replacement checkpoint
     * completed successfully.
     *
     * <p>This is valid only after all writer and read work is terminal. It is
     * not a shortcut around a timed-out drain.</p>
     */
    public synchronized void closeUnclean() {
        closeLease(false);
    }

    private void closeLease(boolean publishCleanShutdown) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        if (startupPublished && publishCleanShutdown) {
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
        boolean channelClosed = false;
        try {
            channel.close();
            channelClosed = true;
        } catch (Exception closeFailure) {
            failure = merge(failure, closeFailure);
        }
        if (channelClosed) {
            reservation.close();
        } else {
            reservation.retain();
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

    private static boolean closeChannel(FileChannel channel) {
        if (channel == null) {
            return true;
        }
        try {
            channel.close();
            return true;
        } catch (Exception ignored) {
            // Preserve the acquisition failure as the primary diagnostic.
            return false;
        }
    }

    static void retainOverlappingChannel(FileChannel channel) {
        if (channel == null) {
            return;
        }
        Thread safetyHold = new Thread(() -> {
            while (channel.isOpen()) {
                LockSupport.park();
            }
        }, "tamework-overlapping-lock-safety-hold");
        safetyHold.setDaemon(true);
        safetyHold.setContextClassLoader(null);
        safetyHold.start();
    }

    private static Path canonicalDirectory(Path dataDirectory) {
        Path normalized = dataDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
            return normalized.toRealPath();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "persistence_engine_lease_failed",
                    failure
            );
        }
    }

    private static FileChannel openLock(Path lockPath) throws Exception {
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "persistence_engine_lock_path_invalid"
            );
        }
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            closeChannel(channel);
            throw new IllegalStateException(
                    "persistence_engine_lock_path_invalid"
            );
        }
        return channel;
    }

    /** Process-wide reservation shared across Tamework plugin classloaders. */
    private static final class JvmReservation implements AutoCloseable {
        private final Properties properties;
        private final String key;
        private final String token;
        private boolean retained;
        private boolean closed;

        private JvmReservation(
                Properties properties,
                String key,
                String token
        ) {
            this.properties = properties;
            this.key = key;
            this.token = token;
        }

        private static JvmReservation acquire(
                Path directory,
                long deadline
        ) {
            Properties properties = System.getProperties();
            String key = JVM_RESERVATION_PREFIX + directory;
            String token = UUID.randomUUID().toString();
            PersistenceEngineLockUnavailableException failure =
                    PersistenceEngineLockUnavailableException.active(
                            true,
                            null
                    );
            boolean contended = false;
            while (true) {
                if (contended && (System.nanoTime() >= deadline
                        || Thread.currentThread().isInterrupted())) {
                    throw failure;
                }
                synchronized (properties) {
                    if (properties.getProperty(key) == null) {
                        properties.setProperty(key, token);
                        return new JvmReservation(
                                properties,
                                key,
                                token
                        );
                    }
                }
                contended = true;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0
                        || Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                LockSupport.parkNanos(Math.min(
                        remaining,
                        LOCK_RETRY_INTERVAL_NANOS
                ));
            }
        }

        private synchronized void retain() {
            retained = true;
        }

        @Override
        public synchronized void close() {
            if (closed || retained) {
                return;
            }
            synchronized (properties) {
                if (token.equals(properties.getProperty(key))) {
                    properties.remove(key);
                }
            }
            closed = true;
        }
    }
}

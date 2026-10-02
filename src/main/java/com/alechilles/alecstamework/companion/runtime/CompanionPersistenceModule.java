package com.alechilles.alecstamework.companion.runtime;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.CompanionFileIo;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.CompanionStore;
import com.alechilles.alecstamework.companion.store.CompanionWriter;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds and owns the companion store, index, writer and loaded-body map (spec 6.7 to 7).
 * {@link #open} reads every owner file before worlds start. When the world has old saves and no
 * new store, or the store cannot be read, the module is not usable: {@link #state()} says why,
 * nothing is written, and companion features refuse (spec 10).
 */
public final class CompanionPersistenceModule {
    public enum State { READY, MIGRATION_REQUIRED, FAILED }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final long FLUSH_INTERVAL_MS = 250L;
    public static final long FLUSH_NOW_TIMEOUT_MS = 5_000L;
    private static final long WARNING_INTERVAL_MS = 60_000L;
    private static final long FOLDER_SIZE_MAX_AGE_MS = 30_000L;

    private final State state;
    @Nullable private final String failure;
    @Nullable private final CompanionStorage.LegacyKind legacyKind;
    @Nullable private final CompanionIndex index;
    @Nullable private final CompanionWriter writer;
    @Nullable private final CompanionStore store;
    @Nullable private final ExecutorService reader;
    @Nullable private final ScheduledExecutorService timers;
    private final List<CompanionIndex.ChangeListener> changeListeners;
    private final LoadedBodies<Ref<EntityStore>> loaded = new LoadedBodies<>();
    @Nullable private final CompanionQueries queries;
    private final Set<UUID> unreadable;
    private final LongSupplier clock;
    private final ThrottledWarnings warnings;
    @Nullable private final Path root;
    private final AtomicBoolean folderSizeRefreshing = new AtomicBoolean();
    private volatile long folderBytes;
    private volatile long folderBytesAtMs;

    private CompanionPersistenceModule(State state, @Nullable String failure,
                                       @Nullable CompanionStorage.LegacyKind legacyKind, @Nullable CompanionIndex index,
                                       @Nullable CompanionWriter writer, @Nullable CompanionStore store,
                                       List<CompanionIndex.ChangeListener> changeListeners,
                                       Set<UUID> unreadable, LongSupplier clock, @Nullable Path root) {
        this.state = state;
        this.root = root;
        this.failure = failure;
        this.legacyKind = legacyKind;
        this.index = index;
        this.writer = writer;
        this.store = store;
        // Snapshot file reads block, so they get their own thread: never a world thread, and
        // never the writer thread, whose flushes must not wait behind a read.
        this.reader = store == null ? null : Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "tamework-companion-reader");
            thread.setDaemon(true);
            return thread;
        });
        // Timed companion work such as summon expiry. It only starts flows, which never block it.
        this.timers = index == null ? null : Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "tamework-companion-timers");
            thread.setDaemon(true);
            return thread;
        });
        this.changeListeners = changeListeners;
        this.queries = index == null ? null : new CompanionQueries(index, loaded);
        this.unreadable = Set.copyOf(unreadable);
        this.clock = clock;
        this.warnings = new ThrottledWarnings(clock, WARNING_INTERVAL_MS);
    }

    /**
     * Opens the store at {@code root}. Blocks on file I/O, so call it before worlds start and
     * never on a world thread. Never throws for store problems; check {@link #state()}.
     *
     * @param legacyDirs directories that may hold old Tamework saves (spec 12.1)
     * @param exists     file-existence check used for old-save detection and {@code meta.json}
     */
    @Nonnull
    public static CompanionPersistenceModule open(@Nonnull Path root, @Nonnull Collection<Path> legacyDirs,
                                                  @Nonnull Predicate<Path> exists, @Nonnull CompanionFileIo io,
                                                  @Nonnull LongSupplier clock, @Nonnull String createdBy) {
        Objects.requireNonNull(clock, "clock");
        CompanionStorage.LegacyKind legacy = CompanionStorage.detectLegacy(root, legacyDirs, exists);
        if (legacy != null) {
            LOGGER.at(Level.WARNING).log("Tamework found companion data from version %s (%s) on this world and no "
                    + "store at %s. This version cannot convert it. Stop the server, run Tamework %s once on this "
                    + "world to convert it, then update to this version again. Companion persistence is disabled "
                    + "until then and the old files are not changed (spec 12.1). To start with an empty store "
                    + "instead, run /tw persistence start-fresh.",
                    legacy.dataVersions(), legacy, root, legacy.converterVersion());
            return failed(State.MIGRATION_REQUIRED, "migration-required", legacy, clock);
        }
        CompanionStore store = new CompanionStore(root, io, clock);
        CompanionStore.LoadResult result;
        try {
            result = store.loadAll();
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers UncheckedIOException and DirectoryIteratorException from
            // listing, and a missing StorageManager.
            LOGGER.at(Level.SEVERE).withCause(e).log("Companion store at %s could not be read; "
                    + "companion persistence is disabled and nothing will be written", root);
            return failed(State.FAILED, String.valueOf(e.getMessage()), null, clock);
        }
        // meta.json goes first so a failure here leaves no writer thread behind. It marks the
        // store as created; the old-save importer (phase 7) skips worlds that have it.
        Path meta = CompanionStorage.metaFile(root);
        if (!exists.test(meta)) {
            try {
                io.write(meta, CompanionStorage.meta(createdBy)).join();
            } catch (RuntimeException e) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                LOGGER.at(Level.SEVERE).withCause(cause).log("Could not write %s; "
                        + "companion persistence is disabled", meta);
                return failed(State.FAILED, String.valueOf(cause.getMessage()), null, clock);
            }
        }
        AtomicReference<CompanionWriter> writerRef = new AtomicReference<>();
        List<CompanionIndex.ChangeListener> listeners = new CopyOnWriteArrayList<>();
        CompanionIndex index = new CompanionIndex(clock, (before, after) -> {
            CompanionWriter w = writerRef.get();
            if (w != null) {
                w.onRecordChanged(before, after);
            }
            for (CompanionIndex.ChangeListener listener : listeners) {
                listener.onChanged(before, after);
            }
        });
        index.load(result.records());
        CompanionWriter writer = new CompanionWriter(index, store, result,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "tamework-companion-writer");
                    thread.setDaemon(true);
                    return thread;
                }), FLUSH_INTERVAL_MS, FLUSH_NOW_TIMEOUT_MS, clock);
        writerRef.set(writer);
        writer.start();
        if (!result.quarantinedFiles().isEmpty() || !result.unreadableIds().isEmpty()) {
            LOGGER.at(Level.WARNING).log("Companion store loaded with %d quarantined files and %d unreadable records",
                    result.quarantinedFiles().size(), result.unreadableIds().size());
        }
        return new CompanionPersistenceModule(State.READY, null, null, index, writer, store, listeners,
                result.unreadableIds(), clock, root);
    }

    /** What {@link #startFresh} did. */
    public enum FreshStart {
        /** The empty store was written; it loads at the next server start. */
        CREATED,
        /** Nothing was written: a store already exists, or no old saves block this world. */
        NOT_NEEDED,
        /** The store could not be written; the cause is in the server log. */
        FAILED
    }

    /**
     * Creates the empty store for a world whose old saves cannot be converted (spec 12.3): it
     * writes only {@code meta.json}, with a fresh-start receipt, so the next {@link #open} loads
     * an empty store instead of reporting {@link State#MIGRATION_REQUIRED}. It never reads, changes
     * or deletes the old files, and it does not change any module that is already open. Blocks on
     * file I/O, so never call it on a world thread. Never throws for store problems.
     */
    @Nonnull
    public static synchronized FreshStart startFresh(@Nonnull Path root, @Nonnull Collection<Path> legacyDirs,
                                                     @Nonnull Predicate<Path> exists, @Nonnull CompanionFileIo io,
                                                     @Nonnull LongSupplier clock, @Nonnull String createdBy) {
        CompanionStorage.LegacyKind legacy = CompanionStorage.detectLegacy(root, legacyDirs, exists);
        if (legacy == null) {
            return FreshStart.NOT_NEEDED;
        }
        Path meta = CompanionStorage.metaFile(root);
        try {
            io.write(meta, CompanionStorage.freshStartMeta(createdBy, legacy, clock.getAsLong())).join();
        } catch (RuntimeException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            LOGGER.at(Level.WARNING).withCause(cause).log("Could not write %s; no fresh companion store was created",
                    meta);
            return FreshStart.FAILED;
        }
        LOGGER.at(Level.INFO).log("Created an empty companion store at %s by operator request. The old %s companion "
                + "data was left unchanged and is no longer used. Restart the server to load the new store.",
                root, legacy.dataVersions());
        return FreshStart.CREATED;
    }

    private static CompanionPersistenceModule failed(State state, String failure,
                                                     @Nullable CompanionStorage.LegacyKind legacyKind,
                                                     LongSupplier clock) {
        return new CompanionPersistenceModule(state, failure, legacyKind, null, null, null, List.of(), Set.of(), clock,
                null);
    }

    @Nonnull public State state() { return state; }
    /** Why the module is not {@link State#READY}, or {@code null} when it is. */
    @Nullable public String failure() { return failure; }
    /** The old saves that block this world; non-null exactly when the state is {@link State#MIGRATION_REQUIRED}. */
    @Nullable public CompanionStorage.LegacyKind legacyKind() { return legacyKind; }
    public boolean ready() { return state == State.READY; }

    /** @throws IllegalStateException when the module is not {@link State#READY}. */
    @Nonnull public CompanionIndex index() { return require(index); }
    /** @throws IllegalStateException when the module is not {@link State#READY}. */
    @Nonnull public CompanionWriter writer() { return require(writer); }
    /** @throws IllegalStateException when the module is not {@link State#READY}. */
    @Nonnull public CompanionQueries queries() { return require(queries); }
    @Nonnull public LoadedBodies<Ref<EntityStore>> loaded() { return loaded; }
    /** True for profile ids whose record could not be decoded at load; their bodies must not be adopted. */
    @Nonnull public Predicate<UUID> unreadable() { return unreadable::contains; }
    /** How many records could not be decoded at load. */
    public int unreadableCount() { return unreadable.size(); }
    /** The companion folder. @throws IllegalStateException when the module is not {@link State#READY}. */
    @Nonnull public Path root() { return require(root); }

    /**
     * The size of the companion folder's files in bytes, as last measured; 0 until the first
     * measurement finishes. A measurement that cannot list the folder keeps the previous value. Never reads files on the caller's thread: when the value is older
     * than 30 s this starts one measurement on the {@code tamework-companion-reader} thread and
     * returns the old value. Nothing runs unless someone asks.
     *
     * @throws IllegalStateException when the module is not {@link State#READY}.
     */
    public long folderBytes() {
        Path folder = require(root);
        long now = clock.getAsLong();
        if ((folderBytesAtMs == 0L || now - folderBytesAtMs >= FOLDER_SIZE_MAX_AGE_MS)
                && folderSizeRefreshing.compareAndSet(false, true)) {
            try {
                require(reader).execute(() -> {
                    try {
                        long measured = measure(folder);
                        if (measured >= 0L) {
                            folderBytes = measured;
                        }
                        folderBytesAtMs = Math.max(1L, clock.getAsLong());
                    } finally {
                        folderSizeRefreshing.set(false);
                    }
                });
            } catch (RejectedExecutionException shutDown) {
                folderSizeRefreshing.set(false);
            }
        }
        return folderBytes;
    }

    /** Sums the regular files under {@code folder}; -1 when it cannot be listed. Blocks on file I/O. */
    private static long measure(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.mapToLong(file -> {
                try {
                    return Files.isRegularFile(file) ? Files.size(file) : 0L;
                } catch (IOException | RuntimeException gone) {
                    return 0L;
                }
            }).sum();
        } catch (IOException | RuntimeException unavailable) {
            return -1L;
        }
    }
    @Nonnull public ThrottledWarnings warnings() { return warnings; }

    /**
     * Adds a listener called after the writer for every applied index change. It runs under the
     * index lock, so it must be cheap and must not block, do I/O or touch worlds. To add it and
     * read the current records with no change missed in between, call this inside
     * {@code index().atomically(...)}.
     *
     * @throws IllegalStateException when the module is not {@link State#READY}.
     */
    public void addChangeListener(@Nonnull CompanionIndex.ChangeListener listener) {
        require(index);
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Adds a listener called for every applied index change after the index lock is released, on
     * the thread that made the change (spec 9), in the order the changes were applied. It may read
     * and write the index. That thread is often a world thread, so the listener must not block and
     * must not touch another world's entities.
     *
     * @throws IllegalStateException when the module is not {@link State#READY}.
     */
    public void addAfterUnlockListener(@Nonnull CompanionIndex.ChangeListener listener) {
        require(index).addAfterUnlockListener(listener);
    }

    /**
     * The module's timer thread for companion timers (summon expiry). Its tasks must not block.
     * {@link #shutdown} stops it before the final flush.
     *
     * @throws IllegalStateException when the module is not {@link State#READY}.
     */
    @Nonnull public ScheduledExecutorService timers() { return require(timers); }

    /**
     * Reads a profile's latest snapshot without blocking the caller (spec 6.5): the snapshot the
     * writer has not written yet when there is one, null when its delete is pending, otherwise the
     * file, read on the {@code tamework-companion-reader} thread. Completes with null when there is
     * no snapshot, and exceptionally when the file cannot be read or the reader is shut down.
     *
     * @throws IllegalStateException when the module is not {@link State#READY}.
     */
    @Nonnull
    public CompletableFuture<SnapshotEnvelope> readSnapshot(@Nonnull UUID profileId) {
        CompanionWriter w = require(writer);
        SnapshotEnvelope pending = w.pendingSnapshot(profileId);
        if (pending != null) {
            return CompletableFuture.completedFuture(pending);
        }
        if (w.isSnapshotDeletePending(profileId)) {
            return CompletableFuture.completedFuture(null);
        }
        CompanionStore s = require(store);
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return s.readSnapshotNow(profileId);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, require(reader));
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Final flush (spec 6.9), given an absolute wall-clock {@code deadlineMs} on this module's
     * clock. Called from the ShutdownEvent handler at priority -28 and again from the plugin's
     * shutdown; the second call returns the first call's result at once. Reads no ECS state.
     * Returns true when nothing was left unwritten, and always true when there is no writer.
     */
    public boolean shutdown(long deadlineMs) {
        // No timer may start a store once the final flush begins.
        if (timers != null) {
            timers.shutdownNow();
        }
        if (reader != null) {
            reader.shutdown();
        }
        return writer == null || writer.shutdown(Math.max(0L, deadlineMs - clock.getAsLong()));
    }

    private <T> T require(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException("Companion persistence is not ready: " + state);
        }
        return value;
    }
}

package com.alechilles.alecstamework.companion.runtime;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.CompanionFileIo;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.CompanionStore;
import com.alechilles.alecstamework.companion.store.CompanionWriter;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
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

    private final State state;
    @Nullable private final String failure;
    @Nullable private final CompanionIndex index;
    @Nullable private final CompanionWriter writer;
    private final LoadedBodies<Ref<EntityStore>> loaded = new LoadedBodies<>();
    @Nullable private final CompanionQueries queries;
    private final Set<UUID> unreadable;
    private final LongSupplier clock;
    private final ThrottledWarnings warnings;

    private CompanionPersistenceModule(State state, @Nullable String failure, @Nullable CompanionIndex index,
                                       @Nullable CompanionWriter writer, Set<UUID> unreadable, LongSupplier clock) {
        this.state = state;
        this.failure = failure;
        this.index = index;
        this.writer = writer;
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
        if (CompanionStorage.detect(root, legacyDirs, exists) == CompanionStorage.Status.MIGRATION_REQUIRED) {
            LOGGER.at(Level.SEVERE).log("Tamework found companion saves from an older version next to %s. "
                    + "Companion persistence is disabled until this world is migrated (spec 12.1).", root);
            return failed(State.MIGRATION_REQUIRED, "migration-required", clock);
        }
        CompanionStore store = new CompanionStore(root, io, clock);
        CompanionStore.LoadResult result;
        try {
            result = store.loadAll();
        } catch (IOException e) {
            LOGGER.at(Level.SEVERE).withCause(e).log("Companion store at %s could not be read; "
                    + "companion persistence is disabled and nothing will be written", root);
            return failed(State.FAILED, String.valueOf(e.getMessage()), clock);
        }
        // meta.json goes first so a failure here leaves no writer thread behind. It marks the
        // store as created; the old-save importer (phase 7) skips worlds that have it.
        Path meta = CompanionStorage.metaFile(root);
        if (!exists.test(meta)) {
            try {
                io.write(meta, CompanionStorage.meta(createdBy)).join();
            } catch (CompletionException | IllegalStateException e) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                LOGGER.at(Level.SEVERE).withCause(cause).log("Could not write %s; "
                        + "companion persistence is disabled", meta);
                return failed(State.FAILED, String.valueOf(cause.getMessage()), clock);
            }
        }
        AtomicReference<CompanionWriter> writerRef = new AtomicReference<>();
        CompanionIndex index = new CompanionIndex(clock, (before, after) -> {
            CompanionWriter w = writerRef.get();
            if (w != null) {
                w.onRecordChanged(before, after);
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
        return new CompanionPersistenceModule(State.READY, null, index, writer, result.unreadableIds(), clock);
    }

    private static CompanionPersistenceModule failed(State state, String failure, LongSupplier clock) {
        return new CompanionPersistenceModule(state, failure, null, null, Set.of(), clock);
    }

    @Nonnull public State state() { return state; }
    /** Why the module is not {@link State#READY}, or {@code null} when it is. */
    @Nullable public String failure() { return failure; }
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
    @Nonnull public ThrottledWarnings warnings() { return warnings; }

    /**
     * Final flush (spec 6.9), given an absolute wall-clock {@code deadlineMs} on this module's
     * clock. Called from the ShutdownEvent handler at priority -28 and again from the plugin's
     * shutdown; the second call returns the first call's result at once. Reads no ECS state.
     * Returns true when nothing was left unwritten, and always true when there is no writer.
     */
    public boolean shutdown(long deadlineMs) {
        return writer == null || writer.shutdown(Math.max(0L, deadlineMs - clock.getAsLong()));
    }

    private <T> T require(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException("Companion persistence is not ready: " + state);
        }
        return value;
    }
}

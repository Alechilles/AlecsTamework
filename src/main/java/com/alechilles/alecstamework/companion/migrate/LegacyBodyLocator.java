package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.migrate.LegacyBodyLocate.SavedEntity;
import com.alechilles.alecstamework.companion.store.CompanionFileIo;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The saved-chunk pass (plan 7 task 13): finds the bodies of imported companions that 5.0 has not
 * seen yet by reading the chunks a world has saved on disk, in the background.
 *
 * <p><b>Why a scan.</b> 4.x stored no position for a companion in an unloaded chunk, and the
 * engine keeps no index from an entity UUID to its chunk, so the only way to learn where such a
 * body is without waiting for a player to walk there is to read the saved chunks. No event can
 * replace it: nothing happens to a chunk nobody loads.</p>
 *
 * <p><b>Scope and cost.</b> One daemon thread at minimum priority, owned by the companion
 * persistence module. Per world it lists the stored chunk indexes once and then reads one chunk
 * at a time: it waits for each chunk before asking for the next, so this pass never has more than
 * one chunk decode in flight. The engine reads and decodes on its own storage threads, never on a
 * world thread, and the chunk is not added to the world. After each chunk the thread sleeps
 * ({@link #pauseMs}): at least as long as the chunk took, so the pass uses at most about half of
 * one storage thread, and longer while the world's ticks run long. It holds one decoded chunk at a
 * time. Index updates go through the index lock and snapshots through the writer queue.</p>
 *
 * <p><b>End.</b> The pass stops as soon as no never-seen import is left. When every world it was
 * given has been read, the records still not found become LOST
 * ({@link LegacyBodyLocate#markNotFound}). A world whose storage cannot be listed is logged and
 * counted as read, so its companions are not left waiting for ever; their bodies still rejoin them
 * when their chunks load.</p>
 *
 * <p><b>Restart and shutdown.</b> Progress is saved every {@value #SAVE_EVERY_CHUNKS} chunks and
 * whenever a world's read ends ({@link LegacyLocateProgress}). {@link #stop} interrupts the thread
 * and waits for it, so no storage read is running when the worlds shut down. A removed world's
 * read stops before its next chunk ({@link #worldRemoved}) and keeps its progress.</p>
 */
public final class LegacyBodyLocator {
    /** One world's saved chunks, as the engine-facing reader gives them. All calls block. */
    public interface Chunks {
        @Nonnull
        String world();

        /**
         * Every stored chunk index of the world.
         *
         * @throws IOException when the storage is not ready yet or cannot be listed
         */
        @Nonnull
        long[] indexes() throws IOException;

        /**
         * The entities saved in one chunk; empty when the chunk is not stored. Waits for the read.
         *
         * @throws TimeoutException when the storage did not answer; the world's read is then given up
         */
        @Nonnull
        List<SavedEntity> load(long index) throws Exception;

        /** False once the world is shutting down or gone. */
        boolean open();

        /**
         * The world's recent tick length as a share of its tick step (1.0 means its ticks use all
         * of their time); NaN when unknown.
         */
        double tickLoad();
    }

    /** How the pass waits; replaced in tests. */
    interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    enum WorldResult { FINISHED, NOTHING_LEFT, STOPPED, FAILED }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final int SAVE_EVERY_CHUNKS = 500;
    private static final int RECOUNT_EVERY_CHUNKS = 200;
    private static final long PROGRESS_LOG_INTERVAL_MS = 60_000L;
    static final int LIST_ATTEMPTS = 5;
    static final long LIST_RETRY_MS = 5_000L;
    static final long BASE_PAUSE_MS = 5L;
    private static final long BUSY_PAUSE_MS = 100L;
    private static final long OVERLOADED_PAUSE_MS = 500L;
    private static final long MAX_PAUSE_MS = 1_000L;
    private static final long STOP_WAIT_MS = 5_000L;

    /** True from {@link #begin} until the pass has ended; read by the recall and panel messages. */
    private static volatile boolean pending;

    private final LegacyBodyLocate decisions;
    private final LegacyLocateProgress progress;
    private final CompanionFileIo io;
    private final Path storeRoot;
    private final LongSupplier clock;
    private final Pause pause;
    private final ConcurrentLinkedQueue<Chunks> queue = new ConcurrentLinkedQueue<>();
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    /** Held around every storage call, so {@link #worldRemoved} returns only when none is running. */
    private final Object storageLock = new Object();
    private final Object lifecycle = new Object();
    @Nullable private Thread thread;
    private boolean begun;
    private volatile boolean stopped;
    private int located;
    private int stale;
    private int failedChunks;
    private boolean saveWarned;

    private LegacyBodyLocator(LegacyBodyLocate decisions, LegacyLocateProgress progress, CompanionFileIo io,
                              Path storeRoot, LongSupplier clock, Pause pause) {
        this.decisions = decisions;
        this.progress = progress;
        this.io = io;
        this.storeRoot = storeRoot;
        this.clock = clock;
        this.pause = pause;
    }

    /**
     * The pass for this store, or null when there is nothing to do: the world was never imported,
     * or no record is never sighted and no earlier pass is unfinished. Null means no thread, no
     * chunk listing and no file is ever touched. Reads the small progress file of an imported
     * store, so call it at start, off the world threads.
     */
    @Nullable
    public static LegacyBodyLocator create(@Nonnull LegacyBodyLocate decisions, @Nonnull CompanionFileIo io,
                                           @Nonnull Path storeRoot, @Nonnull LongSupplier clock) {
        return create(decisions, io, storeRoot, clock, Thread::sleep);
    }

    @Nullable
    static LegacyBodyLocator create(@Nonnull LegacyBodyLocate decisions, @Nonnull CompanionFileIo io,
                                    @Nonnull Path storeRoot, @Nonnull LongSupplier clock, @Nonnull Pause pause) {
        Objects.requireNonNull(decisions, "decisions");
        if (!decisions.imported()) {
            return null;
        }
        LegacyLocateProgress saved = LegacyLocateProgress.load(io, storeRoot);
        int remaining = decisions.remaining();
        if (remaining == 0 && (saved == null || saved.complete)) {
            return null;
        }
        LegacyLocateProgress progress = saved == null ? new LegacyLocateProgress() : saved;
        if (remaining > 0 && progress.complete) {
            // Records are waiting although an earlier pass ended (a restored folder, say): read again.
            progress.reset();
        }
        return new LegacyBodyLocator(decisions, progress, io, storeRoot, clock, pause);
    }

    /** True while a pass is waiting to start or running: never-seen companions are still being located. */
    public static boolean pending() {
        return pending;
    }

    /**
     * Marks the pass as pending before its thread starts, so players are told their companions are
     * being located from the first moment. Call once at plugin start; {@link #start} or
     * {@link #stop} must follow.
     */
    public void begin() {
        synchronized (lifecycle) {
            if (!stopped && !begun) {
                begun = true;
                pending = true;
            }
        }
    }

    /** Adds a world to read. Any thread. Ignored once the pass has ended or was stopped. */
    public void offer(@Nonnull Chunks chunks) {
        if (!stopped) {
            queue.add(Objects.requireNonNull(chunks, "chunks"));
        }
    }

    /**
     * Starts the pass thread over the worlds offered so far. Worlds offered while it runs are read
     * too. Does nothing after {@link #stop} or when it already started.
     */
    public void start() {
        synchronized (lifecycle) {
            if (stopped || thread != null) {
                return;
            }
            begun = true;
            pending = true;
            Thread worker = new Thread(this::runOnThread, "tamework-companion-locate");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            thread = worker;
            worker.start();
        }
    }

    /**
     * A world is being removed: its read stops before its next chunk. Returns only when no storage
     * call of this pass is running, so the world's storage can be closed safely afterwards. Any thread.
     */
    public void worldRemoved(@Nonnull String world) {
        synchronized (storageLock) {
            removed.add(world);
        }
    }

    /**
     * Stops the pass and waits up to five seconds for its thread, which saves its progress first.
     * Call it before the worlds shut down. Safe to call more than once and when the pass never started.
     */
    public void stop() {
        Thread worker;
        synchronized (lifecycle) {
            stopped = true;
            worker = thread;
            if (worker == null) {
                if (begun) {
                    pending = false;
                }
                return;
            }
        }
        queue.clear();
        worker.interrupt();
        try {
            worker.join(STOP_WAIT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void runOnThread() {
        try {
            runPass();
        } catch (InterruptedException interrupted) {
            save(true);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.SEVERE).withCause(failure).log("The search for imported companions in saved chunks "
                    + "stopped on an error. It continues at the next server start");
            save(true);
        } finally {
            pending = false;
        }
    }

    /**
     * Reads every offered world in turn, then marks what was not found. Runs on the pass thread;
     * tests call it directly.
     */
    void runPass() throws InterruptedException {
        long startedAtMs = clock.getAsLong();
        Set<String> read = new HashSet<>();
        while (!stopped) {
            Chunks next = queue.poll();
            if (next == null) {
                break;
            }
            if (!read.add(next.world())) {
                continue;
            }
            if (decisions.remaining() == 0 || scanWorld(next) == WorldResult.NOTHING_LEFT) {
                break;
            }
        }
        if (stopped) {
            save(true);
            return;
        }
        if (read.isEmpty() && decisions.remaining() > 0) {
            // No world was there to read. Marking every companion lost on that evidence would be wrong.
            save(true);
            LOGGER.at(Level.WARNING).log("No world was available to search for imported companions. "
                    + "The search runs again at the next server start");
            return;
        }
        List<UUID> notFound = decisions.markNotFound();
        progress.complete = true;
        save(true);
        // Worlds offered from here on are not read: nothing is left to find.
        stopped = true;
        queue.clear();
        LOGGER.at(Level.INFO).log("Finished locating imported companions in saved chunks after %s: %d located, "
                + "%d not found and now listed as lost (their owners can recover them), %d leftover old bodies "
                + "seen, %d chunks could not be read", duration(clock.getAsLong() - startedAtMs), located,
                notFound.size(), stale, failedChunks);
    }

    /** Reads one world's saved chunks from where its progress says, one chunk at a time. */
    @Nonnull
    WorldResult scanWorld(@Nonnull Chunks chunks) throws InterruptedException {
        String world = chunks.world();
        LegacyLocateProgress.World at = progress.world(world);
        if (at.finished) {
            return WorldResult.FINISHED;
        }
        long[] indexes = list(chunks);
        if (indexes == null) {
            return removed.contains(world) || stopped ? WorldResult.STOPPED : WorldResult.FAILED;
        }
        Arrays.sort(indexes);
        int from = 0;
        if (at.started) {
            int position = Arrays.binarySearch(indexes, at.after);
            from = position >= 0 ? position + 1 : -position - 1;
        }
        long startedAtMs = clock.getAsLong();
        long lastLogAtMs = startedAtMs;
        int remaining = decisions.remaining();
        LOGGER.at(Level.INFO).log("Locating imported companions in the saved chunks of world %s: %d chunks, "
                + "starting at chunk %d, %d companions to find", world, indexes.length, from, remaining);
        for (int i = from; i < indexes.length; i++) {
            if (remaining == 0) {
                save(false);
                logWorldEnd(world, "every companion is located", i, indexes.length, startedAtMs);
                return WorldResult.NOTHING_LEFT;
            }
            long loadStartedAt = System.nanoTime();
            List<SavedEntity> entities;
            synchronized (storageLock) {
                if (stopped || Thread.currentThread().isInterrupted() || removed.contains(world) || !chunks.open()) {
                    save(false);
                    logWorldEnd(world, "stopped", i, indexes.length, startedAtMs);
                    return WorldResult.STOPPED;
                }
                try {
                    entities = chunks.load(indexes[i]);
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (TimeoutException unanswered) {
                    save(false);
                    LOGGER.at(Level.WARNING).log("The storage of world %s did not answer while locating imported "
                            + "companions. Its saved chunks are not read further in this run", world);
                    return WorldResult.FAILED;
                } catch (Exception | LinkageError unreadable) {
                    entities = List.of();
                    if (failedChunks++ == 0) {
                        LOGGER.at(Level.WARNING).withCause(unreadable).log("A saved chunk of world %s could not be "
                                + "read while locating imported companions; it is skipped. Further unreadable "
                                + "chunks are only counted", world);
                    }
                }
            }
            long loadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStartedAt);
            boolean found = false;
            for (SavedEntity entity : entities) {
                switch (decisions.apply(entity)) {
                    case LOCATED -> {
                        located++;
                        found = true;
                    }
                    case STALE -> stale++;
                    case IGNORED -> { }
                }
            }
            at.started = true;
            at.after = indexes[i];
            at.done = i + 1;
            int read = i + 1 - from;
            if (found || read % RECOUNT_EVERY_CHUNKS == 0) {
                // Live sightings find companions too, so the count is refreshed now and then.
                remaining = decisions.remaining();
            }
            if (read % SAVE_EVERY_CHUNKS == 0) {
                save(false);
            }
            long now = clock.getAsLong();
            if (now - lastLogAtMs >= PROGRESS_LOG_INTERVAL_MS) {
                lastLogAtMs = now;
                LOGGER.at(Level.INFO).log("Locating imported companions in world %s: %d of %d chunks read, "
                        + "%d located, %d still missing", world, i + 1, indexes.length, located, remaining);
            }
            pause.sleep(pauseMs(loadMs, chunks.tickLoad()));
        }
        at.finished = true;
        save(false);
        logWorldEnd(world, "all chunks read", indexes.length, indexes.length, startedAtMs);
        return WorldResult.FINISHED;
    }

    /**
     * The sleep after one chunk. At least as long as the chunk took, so reading uses at most about
     * half of one storage thread, and never under {@value #BASE_PAUSE_MS} ms. While the world's
     * recent ticks used more than 60% of their time the pause is at least 100 ms, above 90% at
     * least 500 ms. Never above one second.
     */
    static long pauseMs(long loadMs, double tickLoad) {
        long millis = Math.max(BASE_PAUSE_MS, loadMs);
        if (tickLoad >= 0.9) {
            millis = Math.max(millis, OVERLOADED_PAUSE_MS);
        } else if (tickLoad >= 0.6) {
            millis = Math.max(millis, BUSY_PAUSE_MS);
        }
        return Math.min(millis, MAX_PAUSE_MS);
    }

    /** Lists the world's chunk indexes; a storage that is not ready is asked again a few times. Null when given up. */
    @Nullable
    private long[] list(Chunks chunks) throws InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= LIST_ATTEMPTS; attempt++) {
            synchronized (storageLock) {
                if (stopped || removed.contains(chunks.world()) || !chunks.open()) {
                    return null;
                }
                try {
                    return chunks.indexes();
                } catch (IOException notReady) {
                    last = notReady;
                }
            }
            if (attempt < LIST_ATTEMPTS) {
                pause.sleep(LIST_RETRY_MS);
            }
        }
        LOGGER.at(Level.WARNING).withCause(last).log("The saved chunks of world %s could not be listed, so imported "
                + "companions in it are not located by this run. Their bodies are still matched when their chunks "
                + "load", chunks.world());
        return null;
    }

    private void logWorldEnd(String world, String why, int read, int total, long startedAtMs) {
        LOGGER.at(Level.INFO).log("Locating imported companions in world %s ended after %s (%s): %d of %d chunks "
                + "read, %d located so far", world, duration(clock.getAsLong() - startedAtMs), why, read, total,
                located);
    }

    /** Writes the progress file. {@code wait} holds the thread until the write is done (end of the pass). */
    private void save(boolean wait) {
        try {
            var written = progress.save(io, storeRoot);
            if (wait) {
                written.get(STOP_WAIT_MS / 2, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError failure) {
            if (!saveWarned) {
                saveWarned = true;
                LOGGER.at(Level.WARNING).withCause(failure).log("Could not save %s; after a restart the search for "
                        + "imported companions repeats some chunks", LegacyLocateProgress.FILE_NAME);
            }
        }
    }

    private static String duration(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return seconds >= 60L ? (seconds / 60L) + " min " + (seconds % 60L) + " s" : seconds + " s";
    }
}

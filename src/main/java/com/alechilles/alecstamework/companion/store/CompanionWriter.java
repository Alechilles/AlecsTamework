package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Write-behind for the companion store (spec 6.9). Record changes mark owner files dirty;
 * one executor thread writes snapshots first, then each dirty owner file, every flush
 * interval or immediately on {@link #flushNow(UUID)}. When a record changes owner, the new
 * owner's file is written before the old owner's. Each file is written independently: a
 * failed write keeps only that item pending and retries with backoff, and an owner file that
 * references a snapshot whose write failed waits for that snapshot. Nothing that has not been
 * written is dropped.
 *
 * <p>Lock order is index lock, then writer lock: {@link #onRecordChanged} runs under the index
 * lock, and the flush reads the index (which takes no lock) while holding the writer lock.
 */
public final class CompanionWriter {
    public record Status(int pendingOwners, int pendingSnapshots, @Nullable String lastFailure, long lastFlushAtMs) {
    }

    private static final long MAX_BACKOFF_MS = 30_000L;

    private final CompanionIndex index;
    private final CompanionStore store;
    private final ScheduledExecutorService executor;
    private final long flushIntervalMs;
    private final long flushNowTimeoutMs;
    private final LongSupplier clock;
    private final Map<String, List<BsonDocument>> preserved;
    private final Map<String, Long> versions;

    private final Object lock = new Object();
    private final Set<String> dirtyOwners = new LinkedHashSet<>();
    private final Map<UUID, SnapshotEnvelope> pendingSnapshots = new LinkedHashMap<>();
    private final Set<UUID> pendingSnapshotDeletes = new LinkedHashSet<>();
    private final Map<String, List<CompletableFuture<Void>>> waiters = new HashMap<>();
    private boolean closed;
    @Nullable
    private Boolean shutdownResult;
    private int flushesInFlight;
    private long backoffMs;
    private long nextRetryAtMs;
    private volatile String lastFailure;
    private volatile long lastFlushAtMs;

    /**
     * @param executor a dedicated single-thread executor. The writer owns it: flushes rely on
     *                 running one at a time, and {@link #shutdown(long)} shuts it down.
     */
    public CompanionWriter(@Nonnull CompanionIndex index, @Nonnull CompanionStore store,
                           @Nonnull CompanionStore.LoadResult loaded, @Nonnull ScheduledExecutorService executor,
                           long flushIntervalMs, long flushNowTimeoutMs, @Nonnull LongSupplier clock) {
        this.index = Objects.requireNonNull(index, "index");
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.flushIntervalMs = flushIntervalMs;
        this.flushNowTimeoutMs = flushNowTimeoutMs;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.preserved = new HashMap<>(loaded.unreadableRecords());
        this.versions = new HashMap<>(loaded.versions());
    }

    /** Starts the periodic flush. */
    public void start() {
        executor.scheduleWithFixedDelay(this::flushSafely, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Index listener. On an owner change the new owner is queued before the old one, even when
     * the old owner was already dirty, so the owner that lost the record is written last.
     */
    public void onRecordChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        String afterKey = CompanionStore.ownerKey(after.ownerUuid());
        String beforeKey = before == null ? null : CompanionStore.ownerKey(before.ownerUuid());
        synchronized (lock) {
            if (beforeKey == null || beforeKey.equals(afterKey)) {
                dirtyOwners.add(afterKey);
                return;
            }
            dirtyOwners.remove(beforeKey);
            dirtyOwners.add(afterKey);
            dirtyOwners.add(beforeKey);
        }
    }

    /**
     * Queues a snapshot write. Caller contract: queue the snapshot before the index update that
     * references it, so any flush that sees the reference also writes the snapshot first.
     */
    public void queueSnapshot(@Nonnull SnapshotEnvelope snapshot) {
        synchronized (lock) {
            pendingSnapshotDeletes.remove(snapshot.profileId());
            pendingSnapshots.put(snapshot.profileId(), snapshot);
        }
    }

    public void queueSnapshotDelete(@Nonnull UUID profileId) {
        synchronized (lock) {
            pendingSnapshots.remove(profileId);
            pendingSnapshotDeletes.add(profileId);
        }
    }

    /**
     * Completes when a flush that started after this call has written the owner's file, or
     * fails when that write fails or {@code flushNowTimeoutMs} passes.
     */
    @Nonnull
    public CompletableFuture<Void> flushNow(@Nullable UUID owner) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        String key = CompanionStore.ownerKey(owner);
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("companion_writer_closed"));
            }
            dirtyOwners.add(key);
            waiters.computeIfAbsent(key, k -> new ArrayList<>()).add(done);
        }
        try {
            executor.execute(this::flushSafely);
        } catch (RejectedExecutionException e) {
            synchronized (lock) {
                List<CompletableFuture<Void>> list = waiters.get(key);
                if (list != null) {
                    list.remove(done);
                    if (list.isEmpty()) {
                        waiters.remove(key);
                    }
                }
            }
            return CompletableFuture.failedFuture(e);
        }
        return done.orTimeout(flushNowTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Final flush on shutdown, then shuts the executor down. Returns true only when the final
     * flush finished in time and nothing is left unwritten. A second call returns the first
     * call's result, or false while the first call is still running.
     */
    public boolean shutdown(long deadlineMs) {
        synchronized (lock) {
            if (closed) {
                return Boolean.TRUE.equals(shutdownResult);
            }
            closed = true;
            nextRetryAtMs = 0;
        }
        boolean finished = false;
        try {
            Future<?> finalFlush = executor.submit(this::flushSafely);
            finalFlush.get(deadlineMs, TimeUnit.MILLISECONDS);
            finished = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastFailure = String.valueOf(e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            lastFailure = String.valueOf(e);
        } finally {
            executor.shutdown();
        }
        synchronized (lock) {
            boolean result = finished && flushesInFlight == 0 && dirtyOwners.isEmpty()
                    && pendingSnapshots.isEmpty() && pendingSnapshotDeletes.isEmpty();
            shutdownResult = result;
            return result;
        }
    }

    @Nonnull
    public Status status() {
        synchronized (lock) {
            return new Status(dirtyOwners.size(), pendingSnapshots.size() + pendingSnapshotDeletes.size(), lastFailure, lastFlushAtMs);
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (Throwable t) {
            lastFailure = String.valueOf(t);
        }
    }

    /** Runs only on the executor thread. */
    private void flush() {
        Map<String, List<CompanionRecord>> owners = new LinkedHashMap<>();
        Map<UUID, SnapshotEnvelope> snapshots;
        Set<UUID> deletes;
        Map<String, List<CompletableFuture<Void>>> taken = new HashMap<>();
        synchronized (lock) {
            boolean someoneWaiting = !waiters.isEmpty();
            if (!someoneWaiting && clock.getAsLong() < nextRetryAtMs) {
                return;
            }
            if (dirtyOwners.isEmpty() && pendingSnapshots.isEmpty() && pendingSnapshotDeletes.isEmpty()) {
                return;
            }
            // Records are captured together with the snapshots, under the writer lock, so an
            // owner file never references a snapshot queued (per the caller contract) after it.
            for (String owner : dirtyOwners) {
                owners.put(owner, index.fileRecords(CompanionStore.ownerOf(owner)));
                List<CompletableFuture<Void>> w = waiters.remove(owner);
                if (w != null) {
                    taken.put(owner, w);
                }
            }
            dirtyOwners.clear();
            snapshots = new LinkedHashMap<>(pendingSnapshots);
            pendingSnapshots.clear();
            deletes = new LinkedHashSet<>(pendingSnapshotDeletes);
            pendingSnapshotDeletes.clear();
            flushesInFlight++;
        }

        // Whatever is still in these after the loops was not written and goes back in the queue.
        Map<UUID, SnapshotEnvelope> snapshotsLeft = new LinkedHashMap<>(snapshots);
        Set<UUID> deletesLeft = new LinkedHashSet<>(deletes);
        Set<String> ownersLeft = new LinkedHashSet<>(owners.keySet());
        Map<UUID, Throwable> failedSnapshots = new HashMap<>();
        Throwable failure = null;
        try {
            for (SnapshotEnvelope snapshot : snapshots.values()) {
                try {
                    store.writeSnapshot(snapshot).join();
                    snapshotsLeft.remove(snapshot.profileId());
                } catch (Throwable t) {
                    failure = unwrap(t);
                    failedSnapshots.put(snapshot.profileId(), failure);
                }
            }
            for (UUID profileId : deletes) {
                try {
                    store.deleteSnapshot(profileId).join();
                    deletesLeft.remove(profileId);
                } catch (Throwable t) {
                    failure = unwrap(t);
                }
            }
            for (Map.Entry<String, List<CompanionRecord>> entry : owners.entrySet()) {
                String owner = entry.getKey();
                List<CompanionRecord> records = entry.getValue();
                Throwable blocked = snapshotFailureFor(records, failedSnapshots);
                if (blocked != null) {
                    complete(taken.remove(owner), blocked);
                    continue;
                }
                try {
                    long version = versions.merge(owner, 1L, Long::sum);
                    store.writeOwner(owner, version, records, preserved.get(owner)).join();
                    ownersLeft.remove(owner);
                    complete(taken.remove(owner), null);
                } catch (Throwable t) {
                    failure = unwrap(t);
                    complete(taken.remove(owner), failure);
                }
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            synchronized (lock) {
                snapshotsLeft.forEach((id, snapshot) -> {
                    if (!pendingSnapshotDeletes.contains(id)) {
                        pendingSnapshots.putIfAbsent(id, snapshot);
                    }
                });
                for (UUID id : deletesLeft) {
                    if (!pendingSnapshots.containsKey(id)) {
                        pendingSnapshotDeletes.add(id);
                    }
                }
                dirtyOwners.addAll(ownersLeft);
                if (failure != null) {
                    backoffMs = backoffMs == 0 ? 1_000L : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                    nextRetryAtMs = clock.getAsLong() + backoffMs;
                } else {
                    backoffMs = 0;
                    nextRetryAtMs = 0;
                }
                flushesInFlight--;
            }
            if (failure != null) {
                lastFailure = String.valueOf(failure);
            } else {
                lastFailure = null;
                lastFlushAtMs = clock.getAsLong();
            }
            Throwable leftover = failure != null ? failure : new IllegalStateException("companion_flush_incomplete");
            taken.values().forEach(w -> complete(w, leftover));
        }
    }

    @Nullable
    private static Throwable snapshotFailureFor(List<CompanionRecord> records, Map<UUID, Throwable> failedSnapshots) {
        if (failedSnapshots.isEmpty()) {
            return null;
        }
        for (CompanionRecord record : records) {
            Throwable failure = failedSnapshots.get(record.profileId());
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    private static void complete(@Nullable List<CompletableFuture<Void>> futures, @Nullable Throwable failure) {
        if (futures == null) {
            return;
        }
        for (CompletableFuture<Void> f : futures) {
            if (failure == null) {
                f.complete(null);
            } else {
                f.completeExceptionally(failure);
            }
        }
    }
}

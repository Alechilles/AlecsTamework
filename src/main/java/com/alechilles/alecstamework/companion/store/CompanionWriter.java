package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * one executor thread writes snapshots first, then each dirty owner file, then snapshot
 * deletes, every flush interval or immediately on {@link #flushNow(UUID)}. Each file is written
 * independently: a failed write keeps only that item pending and retries with backoff. Three
 * ordering rules keep every record on disk through a crash: an owner file that references a
 * snapshot whose write failed waits for that snapshot; after a record changes owner the old
 * owner's file is not rewritten until the new owner's file has been written; and a snapshot is
 * deleted only once the owner file that held its record no longer needs it. Nothing that has
 * not been written is dropped.
 *
 * <p>Lock order is index lock, then writer lock: {@link #onRecordChanged} runs under the index
 * lock, and the flush captures its work under both, in that order. No I/O runs under either.
 * Never wait on the writer ({@code flushNow(...).join()} or {@code get()}, {@link #shutdown})
 * while holding the index lock, including inside {@link CompanionIndex#atomically}: the writer
 * thread takes the index lock in its capture step, so that wait blocks until it times out.
 */
public final class CompanionWriter {
    public record Status(int pendingOwners, int pendingSnapshots, @Nullable String lastFailure, long lastFlushAtMs) {
    }

    private static final long MAX_BACKOFF_MS = 30_000L;

    /** Work taken by one flush. */
    private record Batch(
            Map<String, List<CompanionRecord>> owners,
            Map<UUID, SnapshotEnvelope> snapshots,
            /* Profile id to the owner key of its record at capture, or null for no record. */
            Map<UUID, String> deletes,
            Map<String, List<CompletableFuture<Void>>> taken,
            Map<String, Map<String, Long>> waitsFor
    ) {
    }

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
    /**
     * Old owner key to the new owner keys it must wait for, each with the sequence number of the
     * latest transfer between them. An edge is removed only when the new owner's file has been
     * written from a capture that already included that transfer.
     */
    private final Map<String, Map<String, Long>> waitsFor = new HashMap<>();
    private long transferSeq;
    private boolean closed;
    @Nullable
    private Boolean shutdownResult;
    private int flushesInFlight;
    /** The batch the running flush took, so snapshot reads still see it until it is done. */
    @Nullable
    private Batch inFlight;
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
     * the old owner was already dirty, and the old owner is made to wait until the new owner's
     * file has been written.
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
            waitsFor.computeIfAbsent(beforeKey, k -> new HashMap<>()).put(afterKey, ++transferSeq);
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
     * The snapshot the writer still has to write (queued or in the running flush) for this
     * profile, or {@code null}. Snapshot reads must consult the writer before disk: a pending
     * snapshot is newer than the file, and when {@link #isSnapshotDeletePending} is true the file
     * is about to go and must be treated as absent. Phase 2 composition wires this into reads.
     */
    @Nullable
    public SnapshotEnvelope pendingSnapshot(@Nonnull UUID profileId) {
        synchronized (lock) {
            if (pendingSnapshotDeletes.contains(profileId)) {
                return null;
            }
            SnapshotEnvelope queued = pendingSnapshots.get(profileId);
            if (queued != null || inFlight == null || inFlight.deletes().containsKey(profileId)) {
                return queued;
            }
            return inFlight.snapshots().get(profileId);
        }
    }

    /** True when the latest queued change to this profile's snapshot is a delete not yet done. */
    public boolean isSnapshotDeletePending(@Nonnull UUID profileId) {
        synchronized (lock) {
            if (pendingSnapshotDeletes.contains(profileId)) {
                return true;
            }
            if (pendingSnapshots.containsKey(profileId)) {
                return false;
            }
            return inFlight != null && inFlight.deletes().containsKey(profileId);
        }
    }

    /**
     * Completes when a flush that started after this call has written the owner's file, or
     * fails when that write fails or {@code flushNowTimeoutMs} passes. After a record moved from
     * this owner to another, this owner's file waits for the new owner's file, so a flushNow for
     * the old owner keeps failing while the new owner's file keeps failing to write.
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
     * Final flush on shutdown, then shuts the executor down. {@code timeoutMs} is how long to
     * wait for the final flush, not an absolute time. Returns true only when the final flush
     * finished within it and nothing is left unwritten. A second call returns the first call's
     * result, or false while the first call is still running.
     */
    public boolean shutdown(long timeoutMs) {
        synchronized (lock) {
            if (closed) {
                return Boolean.TRUE.equals(shutdownResult);
            }
            closed = true;
        }
        boolean finished = false;
        try {
            Future<?> finalFlush = executor.submit(this::flushSafely);
            finalFlush.get(timeoutMs, TimeUnit.MILLISECONDS);
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

    /**
     * Takes the pending work, or returns {@code null} when there is none or backoff applies.
     * Runs under the index lock and then the writer lock, so no index update is half-seen and an
     * owner file never references a snapshot queued (per the caller contract) after it.
     */
    @Nullable
    private Batch capture() {
        return index.atomically(() -> {
            synchronized (lock) {
                boolean someoneWaiting = !waiters.isEmpty();
                if (!closed && !someoneWaiting && clock.getAsLong() < nextRetryAtMs) {
                    return null;
                }
                if (dirtyOwners.isEmpty() && pendingSnapshots.isEmpty() && pendingSnapshotDeletes.isEmpty()) {
                    return null;
                }
                Map<String, List<CompanionRecord>> owners = new LinkedHashMap<>();
                Map<String, List<CompletableFuture<Void>>> taken = new HashMap<>();
                for (String owner : dirtyOwners) {
                    owners.put(owner, index.fileRecords(CompanionStore.ownerOf(owner)));
                    List<CompletableFuture<Void>> w = waiters.remove(owner);
                    if (w != null) {
                        taken.put(owner, w);
                    }
                }
                dirtyOwners.clear();
                Map<String, Map<String, Long>> edges = new HashMap<>();
                waitsFor.forEach((from, to) -> edges.put(from, new HashMap<>(to)));
                Map<UUID, String> deletes = new LinkedHashMap<>();
                for (UUID id : pendingSnapshotDeletes) {
                    CompanionRecord record = index.get(id);
                    deletes.put(id, record == null ? null : CompanionStore.ownerKey(record.ownerUuid()));
                }
                Batch batch = new Batch(owners, new LinkedHashMap<>(pendingSnapshots), deletes, taken, edges);
                pendingSnapshots.clear();
                pendingSnapshotDeletes.clear();
                flushesInFlight++;
                inFlight = batch;
                return batch;
            }
        });
    }

    /** Runs only on the executor thread. */
    private void flush() {
        Batch batch = capture();
        if (batch == null) {
            return;
        }
        Map<String, List<CompletableFuture<Void>>> taken = batch.taken();
        // Whatever is still in these after the loops was not written and goes back in the queue.
        Map<UUID, SnapshotEnvelope> snapshotsLeft = new LinkedHashMap<>(batch.snapshots());
        Set<UUID> deletesLeft = new LinkedHashSet<>(batch.deletes().keySet());
        Set<String> ownersLeft = new LinkedHashSet<>(batch.owners().keySet());
        Set<String> ownersWritten = new HashSet<>();
        Map<String, Throwable> ownersFailed = new HashMap<>();
        Map<UUID, Throwable> failedSnapshots = new HashMap<>();
        // Waiters are failed only after lastFailure is set, so a woken waiter sees the cause.
        List<Runnable> failedWaiters = new ArrayList<>();
        Throwable failure = null;
        try {
            for (SnapshotEnvelope snapshot : batch.snapshots().values()) {
                try {
                    store.writeSnapshot(snapshot).join();
                    snapshotsLeft.remove(snapshot.profileId());
                } catch (Throwable t) {
                    failure = unwrap(t);
                    failedSnapshots.put(snapshot.profileId(), failure);
                }
            }
            // An old owner is deferred until the new owners it waits for have been tried in this
            // flush. If no pass makes progress the remaining owners form a transfer cycle, which
            // no write order can make crash safe, so they are written in dirty order.
            List<String> todo = new ArrayList<>(batch.owners().keySet());
            boolean ignoreOrder = false;
            while (!todo.isEmpty()) {
                boolean progress = false;
                for (Iterator<String> it = todo.iterator(); it.hasNext(); ) {
                    String owner = it.next();
                    List<CompanionRecord> records = batch.owners().get(owner);
                    Throwable blocked = snapshotFailureFor(records, failedSnapshots);
                    boolean deferred = false;
                    for (String dep : batch.waitsFor().getOrDefault(owner, Map.of()).keySet()) {
                        if (blocked != null) {
                            break;
                        }
                        if (ownersWritten.contains(dep)) {
                            continue;
                        }
                        if (todo.contains(dep)) {
                            deferred |= !ignoreOrder;
                            continue;
                        }
                        blocked = new IllegalStateException(
                                "companion_owner_waiting_for_new_owner " + dep, ownersFailed.get(dep));
                    }
                    if (blocked == null && deferred) {
                        continue;
                    }
                    it.remove();
                    progress = true;
                    if (blocked != null) {
                        failure = blocked;
                        ownersFailed.put(owner, blocked);
                        deferFailure(failedWaiters, taken.remove(owner), blocked);
                        continue;
                    }
                    try {
                        long version = versions.merge(owner, 1L, Long::sum);
                        store.writeOwner(owner, version, records, preserved.get(owner)).join();
                        ownersLeft.remove(owner);
                        ownersWritten.add(owner);
                        complete(taken.remove(owner), null);
                    } catch (Throwable t) {
                        failure = unwrap(t);
                        ownersFailed.put(owner, failure);
                        deferFailure(failedWaiters, taken.remove(owner), failure);
                    }
                }
                if (!progress) {
                    ignoreOrder = true;
                }
            }
            // A snapshot is deleted only after the file that held its record at capture no longer
            // points at it: that owner was written in this flush, was not dirty (its file on disk
            // is current), or the profile had no record. Otherwise the delete waits for a retry.
            for (Map.Entry<UUID, String> delete : batch.deletes().entrySet()) {
                String owner = delete.getValue();
                if (owner != null && batch.owners().containsKey(owner) && !ownersWritten.contains(owner)) {
                    continue;
                }
                try {
                    store.deleteSnapshot(delete.getKey()).join();
                    deletesLeft.remove(delete.getKey());
                } catch (Throwable t) {
                    failure = unwrap(t);
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
                releaseTransfers(batch.waitsFor(), ownersWritten);
                if (failure != null) {
                    backoffMs = backoffMs == 0 ? 1_000L : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                    nextRetryAtMs = clock.getAsLong() + backoffMs;
                } else {
                    backoffMs = 0;
                    nextRetryAtMs = 0;
                }
                flushesInFlight--;
                inFlight = null;
            }
            if (failure != null) {
                lastFailure = String.valueOf(failure);
            } else {
                lastFailure = null;
                lastFlushAtMs = clock.getAsLong();
            }
            failedWaiters.forEach(Runnable::run);
            Throwable leftover = failure != null ? failure : new IllegalStateException("companion_flush_incomplete");
            taken.values().forEach(w -> complete(w, leftover));
        }
    }

    /**
     * Drops each captured transfer edge whose new owner was written in this flush, unless a newer
     * transfer between the same owners happened after the capture. Caller holds the writer lock.
     */
    private void releaseTransfers(Map<String, Map<String, Long>> captured, Set<String> written) {
        if (written.isEmpty()) {
            return;
        }
        captured.forEach((from, to) -> to.forEach((newOwner, seq) -> {
            if (!written.contains(newOwner)) {
                return;
            }
            Map<String, Long> live = waitsFor.get(from);
            if (live != null) {
                live.remove(newOwner, seq);
                if (live.isEmpty()) {
                    waitsFor.remove(from);
                }
            }
        }));
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

    private static void deferFailure(List<Runnable> out, @Nullable List<CompletableFuture<Void>> futures,
                                     Throwable failure) {
        if (futures != null) {
            out.add(() -> complete(futures, failure));
        }
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

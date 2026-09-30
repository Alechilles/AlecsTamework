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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Write-behind for the companion store (spec 6.9). Record changes mark owner files dirty;
 * one executor thread writes snapshots first, then each dirty owner file, every flush
 * interval or immediately on {@link #flushNow(UUID)}. A failed write keeps the state dirty
 * and retries with backoff; nothing that has not been written is dropped.
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
    private long backoffMs;
    private long nextRetryAtMs;
    private volatile String lastFailure;
    private volatile long lastFlushAtMs;

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

    /** Index listener: files a change under its new owner first, then its old owner. */
    public void onRecordChanged(@Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        synchronized (lock) {
            dirtyOwners.add(CompanionStore.ownerKey(after.ownerUuid()));
            if (before != null) {
                dirtyOwners.add(CompanionStore.ownerKey(before.ownerUuid()));
            }
        }
    }

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
        executor.execute(this::flushSafely);
        return done.orTimeout(flushNowTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Final flush on shutdown. Returns true when nothing was left unwritten. */
    public boolean shutdown(long deadlineMs) {
        synchronized (lock) {
            closed = true;
            nextRetryAtMs = 0;
        }
        Future<?> finalFlush = executor.submit(this::flushSafely);
        try {
            finalFlush.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            lastFailure = String.valueOf(e);
        } finally {
            executor.shutdown();
        }
        synchronized (lock) {
            return dirtyOwners.isEmpty() && pendingSnapshots.isEmpty() && pendingSnapshotDeletes.isEmpty();
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
        List<String> owners;
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
            owners = new ArrayList<>(dirtyOwners);
            dirtyOwners.clear();
            snapshots = new LinkedHashMap<>(pendingSnapshots);
            pendingSnapshots.clear();
            deletes = new LinkedHashSet<>(pendingSnapshotDeletes);
            pendingSnapshotDeletes.clear();
            for (String owner : owners) {
                List<CompletableFuture<Void>> w = waiters.remove(owner);
                if (w != null) {
                    taken.put(owner, w);
                }
            }
        }

        int written = 0;
        try {
            for (SnapshotEnvelope snapshot : snapshots.values()) {
                store.writeSnapshot(snapshot).join();
            }
            for (UUID profileId : deletes) {
                store.deleteSnapshot(profileId).join();
            }
            for (String owner : owners) {
                long version = versions.merge(owner, 1L, Long::sum);
                store.writeOwner(owner, version, index.fileRecords(CompanionStore.ownerOf(owner)), preserved.get(owner)).join();
                written++;
                complete(taken.remove(owner), null);
            }
            synchronized (lock) {
                backoffMs = 0;
                nextRetryAtMs = 0;
            }
            lastFailure = null;
            lastFlushAtMs = clock.getAsLong();
        } catch (RuntimeException failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            lastFailure = String.valueOf(cause);
            synchronized (lock) {
                snapshots.forEach(pendingSnapshots::putIfAbsent);
                pendingSnapshotDeletes.addAll(deletes);
                dirtyOwners.addAll(owners.subList(written, owners.size()));
                backoffMs = backoffMs == 0 ? 1_000L : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                nextRetryAtMs = clock.getAsLong() + backoffMs;
            }
            taken.values().forEach(w -> complete(w, cause));
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

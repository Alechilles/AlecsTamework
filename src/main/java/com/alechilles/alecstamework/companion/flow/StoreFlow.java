package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Puts a LIVE companion into storage (spec 8.4). With its body loaded, the caller-supplied
 * capture snapshots it on its world thread; otherwise the stored snapshot stands and no chunk is
 * loaded. Commit, flush, then remove the body; a flush failure reverts and re-registers it.
 *
 * <p>The record moves to STORED with generation+1 and the body is unregistered under the index
 * lock. The body is removed only once the commit is written. When a newer change replaced the
 * commit ({@link Result#CONFLICT}) the body is stale under the generation fence and is removed too.
 *
 * <p>Continuations run on whichever thread completes the capture or the flush. {@code removeBody}
 * must hand its entity work to the owning world thread itself.
 *
 * @param <R> the body reference type (Ref&lt;EntityStore&gt; in production)
 */
public final class StoreFlow<R> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public enum Result { STORED, NOT_FOUND, NOT_LIVE, NO_SNAPSHOT, CONFLICT, COMMIT_FAILED }

    /** Snapshots a loaded body on its world thread; completes null when the body is gone. */
    public interface BodyCapture<R> {
        @Nonnull
        CompletableFuture<CapturedBody> capture(@Nonnull R body);
    }

    public record CapturedBody(@Nonnull BsonDocument snapshotData, @Nonnull CompanionSummary summary) {
        public CapturedBody {
            Objects.requireNonNull(snapshotData, "snapshotData");
            Objects.requireNonNull(summary, "summary");
        }
    }

    private final CompanionIndex index;
    private final LoadedBodies<R> loaded;
    private final BodyCapture<R> capture;
    private final Function<UUID, CompletableFuture<SnapshotEnvelope>> storedSnapshot;
    private final BiConsumer<UUID, SnapshotEnvelope> queueSnapshot;
    private final Function<UUID, CompletableFuture<Void>> flushOwner;
    private final BiConsumer<UUID, R> removeBody;
    private final LongSupplier clock;

    /**
     * @param storedSnapshot reads a profile's stored snapshot off the world thread; completes null when there is none
     * @param queueSnapshot  queues a snapshot write for a profile
     * @param flushOwner     writes the given owner's file now (null for unowned); fails on error or timeout
     * @param removeBody     removes the stored companion's body from its world; called only after the commit is written
     * @param clock          wall clock, stamped as the new snapshot's time
     */
    public StoreFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded, @Nonnull BodyCapture<R> capture,
                     @Nonnull Function<UUID, CompletableFuture<SnapshotEnvelope>> storedSnapshot,
                     @Nonnull BiConsumer<UUID, SnapshotEnvelope> queueSnapshot,
                     @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner,
                     @Nonnull BiConsumer<UUID, R> removeBody, @Nonnull LongSupplier clock) {
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.storedSnapshot = Objects.requireNonNull(storedSnapshot, "storedSnapshot");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.flushOwner = Objects.requireNonNull(flushOwner, "flushOwner");
        this.removeBody = Objects.requireNonNull(removeBody, "removeBody");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Stores the companion for {@code reason}; {@code cooldownUntilMs} is the wall-clock time the
     * next summon is allowed, 0 for none. Never completes exceptionally for an expected failure;
     * the {@link Result} says what happened:
     * <ul>
     *   <li>NOT_FOUND, NOT_LIVE, NO_SNAPSHOT: nothing changed.
     *   <li>CONFLICT before the commit (the record changed while the snapshot was taken or read, or
     *       a body registered while the stored snapshot was read): the companion is still LIVE with
     *       its body registered; the caller may retry.
     *   <li>COMMIT_FAILED when the capture failed, was not a usable snapshot, or the flush failed and
     *       the revert applied: the companion is still LIVE with its body registered.
     *   <li>COMMIT_FAILED when the flush failed but a newer change had already replaced the commit:
     *       the revert does not apply, the record is no longer LIVE (it keeps the newer change) and
     *       the stale body is removed.
     *   <li>CONFLICT after the flush (a newer change replaced the commit): the record keeps that
     *       change and the stale body is removed.
     *   <li>STORED: written, and the body removed.
     * </ul>
     */
    @Nonnull
    public CompletableFuture<Result> store(@Nonnull UUID profileId, @Nonnull StoredReason reason, long cooldownUntilMs) {
        CompanionRecord before = index.get(profileId);
        if (before == null) {
            return CompletableFuture.completedFuture(Result.NOT_FOUND);
        }
        if (before.location().kind() != LocationKind.LIVE) {
            return CompletableFuture.completedFuture(Result.NOT_LIVE);
        }
        R body = loaded.get(profileId);
        if (body == null) {
            return storeUnloaded(before, reason, cooldownUntilMs);
        }
        CompletableFuture<CapturedBody> captured;
        try {
            captured = capture.capture(body);
        } catch (RuntimeException e) {
            captured = CompletableFuture.failedFuture(e);
        }
        return captured.handle((snapshot, error) -> {
            if (error != null) {
                LOGGER.at(Level.WARNING).withCause(error)
                        .log("Could not snapshot companion %s for storage; it stays live", profileId);
                return CompletableFuture.completedFuture(Result.COMMIT_FAILED);
            }
            return snapshot == null ? storeUnloaded(before, reason, cooldownUntilMs)
                    : commit(before, body, snapshot, reason, cooldownUntilMs);
        }).thenCompose(Function.identity());
    }

    /** The body is not loaded (or vanished): the stored snapshot stands. It must be usable for a later recall. */
    private CompletableFuture<Result> storeUnloaded(CompanionRecord before, StoredReason reason, long cooldownUntilMs) {
        return storedSnapshot.apply(before.profileId()).handle((snapshot, error) -> error == null ? snapshot : null)
                .thenCompose(snapshot -> RestoreRules.forSnapshot(before, snapshot, RestoreRules.Reason.RECALL)
                        == RestoreRules.Verdict.ALLOWED
                        ? commit(before, null, null, reason, cooldownUntilMs)
                        : CompletableFuture.completedFuture(Result.NO_SNAPSHOT));
    }

    /** {@code body} and {@code fresh} are both null on the unloaded path, which commits only while no body is registered. */
    private CompletableFuture<Result> commit(CompanionRecord before, @Nullable R body, @Nullable CapturedBody fresh,
                                             StoredReason reason, long cooldownUntilMs) {
        UUID profileId = before.profileId();
        if (fresh != null && RestoreRules.forSnapshot(before,
                new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT, before.generation(), fresh.snapshotData()),
                RestoreRules.Reason.RECALL) != RestoreRules.Verdict.ALLOWED) {
            LOGGER.at(Level.WARNING).log("Companion %s has no usable snapshot to store; it stays live", profileId);
            return CompletableFuture.completedFuture(Result.COMMIT_FAILED);
        }
        Long snapshotAtMs = fresh == null ? null : clock.getAsLong();
        CompanionSummary summary = fresh == null ? null : fresh.summary();
        // The revision check makes a change made while the snapshot was taken or read win. Without a
        // fresh capture, a body that registered meanwhile is not covered by the stored snapshot.
        Commit commit = index.atomically(() -> {
            if (fresh == null && loaded.get(profileId) != null) {
                return null;
            }
            CompanionIndex.Mutation m = index.update(profileId, before.revision(),
                    CompanionTransitions.stored(before, reason, summary, snapshotAtMs, cooldownUntilMs));
            if (!m.applied()) {
                return null;
            }
            return new Commit(m.after(), body != null && loaded.removeIfSame(profileId, body));
        });
        if (commit == null) {
            return CompletableFuture.completedFuture(Result.CONFLICT);
        }
        CompanionRecord after = commit.after();
        if (fresh != null) {
            try {
                queueSnapshot.accept(profileId,
                        new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT, after.generation(), fresh.snapshotData()));
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure)
                        .log("Could not queue the snapshot of stored companion %s; the store is undone", profileId);
                if (!revertCommit(before, commit, body, fresh)) {
                    removeBodySafely(profileId, body);
                }
                return CompletableFuture.completedFuture(Result.COMMIT_FAILED);
            }
        }
        return flush(after).handle((ignored, error) -> error).thenApply(error -> {
            if (error != null) {
                LOGGER.at(Level.WARNING).withCause(error)
                        .log("Storing companion %s was not written; the store is undone", profileId);
                if (!revertCommit(before, commit, body, fresh)) {
                    removeBodySafely(profileId, body);
                }
                return Result.COMMIT_FAILED;
            }
            CompanionRecord now = index.get(profileId);
            removeBodySafely(profileId, body);
            // A change the store does not own (an extension write, say) does not undo it.
            return RestoreFlow.sameHolder(after, now) ? Result.STORED : Result.CONFLICT;
        });
    }

    private CompletableFuture<Void> flush(CompanionRecord after) {
        try {
            return flushOwner.apply(after.ownerUuid());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Undoes an unwritten commit: the record goes back to LIVE and the body back into the registry.
     * A fresh snapshot is queued again at the old generation, because a snapshot newer than its
     * record cannot be restored. Returns false, changing nothing, when the record changed since the commit.
     */
    private boolean revertCommit(CompanionRecord before, Commit commit, @Nullable R body, @Nullable CapturedBody fresh) {
        UUID profileId = before.profileId();
        return index.atomically(() -> {
            if (!RestoreFlow.revertHolder(index, commit.after(), before)) {
                return false;
            }
            if (commit.unregistered()) {
                loaded.put(profileId, body);
            }
            if (fresh != null) {
                try {
                    queueSnapshot.accept(profileId,
                            new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT, before.generation(), fresh.snapshotData()));
                } catch (RuntimeException failure) {
                    // The record and registry are already back; only the snapshot write is missing.
                    LOGGER.at(Level.WARNING).withCause(failure)
                            .log("Could not re-queue the snapshot of companion %s after an undone store", profileId);
                }
            }
            return true;
        });
    }

    /** A failed removal is logged; the stale body is removed by the generation fence when it next loads. */
    private void removeBodySafely(UUID profileId, @Nullable R body) {
        if (body == null) {
            return;
        }
        try {
            removeBody.accept(profileId, body);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Could not remove the body of stored companion %s", profileId);
        }
    }

    private record Commit(@Nonnull CompanionRecord after, boolean unregistered) {
    }
}

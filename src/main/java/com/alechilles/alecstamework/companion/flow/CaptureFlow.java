package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Commit-first capture into an item (spec 8.2). The caller has taken the snapshot on the body's
 * world thread; this flow commits ITEM, flushes, and only then lets the caller remove the body
 * and hand over the item. A flush failure reverts the record and re-registers the body.
 *
 * <p>The caller removes the body and gives the item only on {@link Result#CAPTURED}. After any
 * other result the body stays in the world. When a newer change replaced the commit
 * ({@link Result#CONFLICT}), the body is already unregistered and its stamp is stale, so the
 * generation fence removes it when it next loads.
 *
 * <p>Continuations run on whichever thread completes the flush. The flow touches no live
 * entities; {@code R} is only a registry key for the body.
 *
 * @param <R> the body reference type (Ref&lt;EntityStore&gt; in production)
 */
public final class CaptureFlow<R> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public enum Result { CAPTURED, NOT_CAPTURABLE, CONFLICT, COMMIT_FAILED }

    /** {@code itemRef} is what to write on the item; null unless CAPTURED. */
    public record Outcome(@Nonnull Result result, @Nullable CaptureItemKeys.Ref itemRef) {
    }

    /**
     * One capture. {@code stampedProfileId} null means an unstamped body (wild or owned but never
     * stamped): it gets a fresh record at generation 0. {@code owner} is the record owner after
     * capture (null when {@code ClearsOwner}).
     */
    public record Capture<R>(@Nullable UUID stampedProfileId, long stampedGeneration, @Nonnull R body,
                             @Nonnull CompanionTransitions.BodyFacts facts, @Nullable UUID owner,
                             @Nullable String ownerName, @Nonnull BsonDocument snapshotData) {
        public Capture {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(facts, "facts");
            Objects.requireNonNull(snapshotData, "snapshotData");
        }
    }

    private final CompanionIndex index;
    private final LoadedBodies<R> loaded;
    private final BiConsumer<UUID, SnapshotEnvelope> queueSnapshot;
    private final Function<UUID, CompletableFuture<Void>> flushOwner;

    /**
     * @param queueSnapshot queues a snapshot write for a profile
     * @param flushOwner    writes the given owner's file now (null for unowned); fails on error or timeout
     */
    public CaptureFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                       @Nonnull BiConsumer<UUID, SnapshotEnvelope> queueSnapshot,
                       @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner) {
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.flushOwner = Objects.requireNonNull(flushOwner, "flushOwner");
    }

    /** Never completes exceptionally for an expected failure; the {@link Result} says what happened. */
    @Nonnull
    public CompletableFuture<Outcome> capture(@Nonnull Capture<R> capture) {
        Commit commit = index.atomically(() -> commit(capture));
        if (commit.refusal() != null) {
            return CompletableFuture.completedFuture(new Outcome(commit.refusal(), null));
        }
        CompanionRecord after = commit.after();
        UUID profileId = after.profileId();
        try {
            queueSnapshot.accept(profileId, new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT,
                    after.generation(), capture.snapshotData()));
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Could not queue the snapshot of captured companion %s; the capture is undone", profileId);
            revertSafely(capture, commit);
            return CompletableFuture.completedFuture(new Outcome(Result.COMMIT_FAILED, null));
        }
        return flushOwners(commit.before(), after).handle((ignored, error) -> error)
                .thenApply(error -> {
                    if (error != null) {
                        LOGGER.at(Level.WARNING).withCause(error)
                                .log("Capture of companion %s was not written; the capture is undone", profileId);
                        revertSafely(capture, commit);
                        return new Outcome(Result.COMMIT_FAILED, null);
                    }
                    // A change that kept the item holder (a rename, say) does not undo the capture.
                    CompanionRecord now = index.get(profileId);
                    if (now == null || now.location().kind() != LocationKind.ITEM
                            || now.generation() != after.generation()) {
                        return new Outcome(Result.CONFLICT, null);
                    }
                    return new Outcome(Result.CAPTURED, new CaptureItemKeys.Ref(profileId, after.generation()));
                });
    }

    private Commit commit(Capture<R> capture) {
        CompanionTransitions.BodyFacts facts = capture.facts();
        UUID stamped = capture.stampedProfileId();
        if (stamped != null) {
            CompanionRecord before = index.get(stamped);
            if (before == null || before.location().kind() != LocationKind.LIVE
                    || before.generation() != capture.stampedGeneration()) {
                return Commit.refused(Result.NOT_CAPTURABLE);
            }
            R registered = loaded.get(stamped);
            if (registered != null && !registered.equals(capture.body())) {
                // Another body holds this profile; capturing this one would strand the registered one.
                return Commit.refused(Result.NOT_CAPTURABLE);
            }
            CompanionIndex.Mutation m = index.update(stamped, before.revision(),
                    CompanionTransitions.capturedToItem(before, facts.summary(), capture.owner(), capture.ownerName()));
            if (!m.applied()) {
                return Commit.refused(Result.CONFLICT);
            }
            boolean unregistered = loaded.removeIfSame(stamped, capture.body());
            return new Commit(null, before, m.after(), unregistered);
        }
        if (index.byNpcUuid(facts.npcUuid()) != null) {
            return Commit.refused(Result.NOT_CAPTURABLE);
        }
        CompanionIndex.Mutation m = index.insert(
                CompanionTransitions.newItem(UUID.randomUUID(), facts, capture.owner(), capture.ownerName()));
        return m.applied() ? new Commit(null, null, m.after(), false) : Commit.refused(Result.CONFLICT);
    }

    /** The new owner's file is written first, then the old one's; either failure fails the commit. */
    private CompletableFuture<Void> flushOwners(@Nullable CompanionRecord before, CompanionRecord after) {
        CompletableFuture<Void> first;
        try {
            first = flushOwner.apply(after.ownerUuid());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (before == null || Objects.equals(before.ownerUuid(), after.ownerUuid())) {
            return first;
        }
        return first.thenCompose(v -> flushOwner.apply(before.ownerUuid()));
    }

    /** A failed undo is logged; it never fails the returned future. */
    private void revertSafely(Capture<R> capture, Commit commit) {
        try {
            revertCommit(capture, commit);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Could not undo the capture of companion %s", commit.after().profileId());
        }
    }

    /**
     * Undoes an unwritten commit. A stamped body goes back to its record and registry entry, and
     * the snapshot is queued again at the old generation, because a snapshot newer than its record
     * cannot be restored. A body that never had a record leaves a tombstone, since there is nothing
     * to go back to. Changes nothing when the record changed since the commit.
     */
    private void revertCommit(Capture<R> capture, Commit commit) {
        CompanionRecord after = commit.after();
        UUID profileId = after.profileId();
        CompanionRecord before = commit.before();
        index.atomically(() -> {
            if (before == null) {
                index.update(profileId, after.revision(), CompanionTransitions.released(after));
                return null;
            }
            if (index.revert(profileId, after.revision(), before).applied()) {
                if (commit.unregistered()) {
                    loaded.put(profileId, capture.body());
                }
                queueSnapshot.accept(profileId, new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT,
                        before.generation(), capture.snapshotData()));
            }
            return null;
        });
    }

    private record Commit(@Nullable Result refusal, @Nullable CompanionRecord before,
                          @Nullable CompanionRecord after, boolean unregistered) {
        static Commit refused(Result refusal) {
            return new Commit(refusal, null, null, false);
        }
    }
}

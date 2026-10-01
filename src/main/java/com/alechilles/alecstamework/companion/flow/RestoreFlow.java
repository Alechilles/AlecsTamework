package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Brings a companion back from its snapshot at a destination (spec 6.5, 6.9, 8.5, 8.6). The record
 * moves to LIVE with generation+1 and a new NPC UUID under the index lock, the owner file is
 * written, and only then is the old body removed and the new one spawned. Any failure puts the
 * record back. A change to the record while the restore runs wins: nothing spawns.
 *
 * <p>Continuations run on whichever thread completes the snapshot read, the flush or the spawn
 * (usually the writer thread). {@code removeOldBody} and the {@link Spawner} must therefore hand
 * their entity work to the owning world thread themselves; they must not touch live entities on
 * the calling thread.
 *
 * <p>A spawn that fails after the old body was removed leaves the record back at its old location
 * with no body; a later recall or Recover restores it. The removed body is not re-registered.
 *
 * @param <R> the body reference type (Ref&lt;EntityStore&gt; in production)
 */
public final class RestoreFlow<R> {
    public enum Result { RESTORED, NOT_FOUND, NOT_ALLOWED, COOLDOWN, NO_SNAPSHOT, CONFLICT, COMMIT_FAILED, SPAWN_FAILED }

    /** Where to restore. {@code world} is a world name; the production spawner resolves it. */
    public record Destination(@Nonnull String world, double x, double y, double z, float yaw, float pitch) {
        public Destination {
            Objects.requireNonNull(world, "world");
        }
    }

    /** Spawns the committed companion at the destination; completes true once the body is added. */
    public interface Spawner {
        @Nonnull
        CompletableFuture<Boolean> spawn(@Nonnull CompanionRecord committed, @Nonnull SnapshotEnvelope snapshot,
                                         @Nonnull Destination destination, @Nonnull RestoreRules.Reason reason);
    }

    private final CompanionIndex index;
    private final LoadedBodies<R> loaded;
    private final Function<UUID, CompletableFuture<SnapshotEnvelope>> snapshots;
    private final Function<UUID, CompletableFuture<Void>> flushOwner;
    private final Spawner spawner;
    private final BiConsumer<UUID, R> removeOldBody;
    private final LongSupplier clock;

    /**
     * @param snapshots     reads a profile's snapshot off the world thread; completes with null when there is none
     * @param flushOwner    writes the given owner's file now (null for unowned); fails on error or timeout
     * @param removeOldBody removes the old body from its world; called only after the commit is written
     * @param clock         wall clock, used for the revive cooldown
     */
    public RestoreFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                       @Nonnull Function<UUID, CompletableFuture<SnapshotEnvelope>> snapshots,
                       @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner, @Nonnull Spawner spawner,
                       @Nonnull BiConsumer<UUID, R> removeOldBody, @Nonnull LongSupplier clock) {
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.flushOwner = Objects.requireNonNull(flushOwner, "flushOwner");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.removeOldBody = Objects.requireNonNull(removeOldBody, "removeOldBody");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Never completes exceptionally for an expected failure; the {@link Result} says what happened. */
    @Nonnull
    public CompletableFuture<Result> restore(@Nonnull UUID profileId, @Nonnull RestoreRules.Reason reason,
                                             @Nonnull Destination destination) {
        CompanionRecord before = index.get(profileId);
        RestoreRules.Verdict verdict = RestoreRules.forRecord(before, reason, clock.getAsLong());
        if (verdict != RestoreRules.Verdict.ALLOWED) {
            return CompletableFuture.completedFuture(map(verdict));
        }
        return snapshots.apply(profileId).handle((snapshot, error) -> error == null ? snapshot : null)
                .thenCompose(snapshot -> commitAndSpawn(before, snapshot, reason, destination));
    }

    private CompletableFuture<Result> commitAndSpawn(CompanionRecord before, @Nullable SnapshotEnvelope snapshot,
                                                     RestoreRules.Reason reason, Destination destination) {
        RestoreRules.Verdict snapshotVerdict = RestoreRules.forSnapshot(before, snapshot, reason);
        if (snapshotVerdict != RestoreRules.Verdict.ALLOWED) {
            return CompletableFuture.completedFuture(map(snapshotVerdict));
        }
        UUID profileId = before.profileId();
        UUID newNpcUuid = UUID.randomUUID();
        // The revision check makes a change made while the snapshot was read win.
        Commit<R> commit = index.atomically(() -> {
            CompanionIndex.Mutation m = index.update(profileId, before.revision(), CompanionTransitions.restored(
                    before, destination.world(), destination.x(), destination.y(), destination.z(), newNpcUuid));
            if (!m.applied()) {
                return null;
            }
            R old = loaded.get(profileId);
            if (old != null) {
                loaded.removeIfSame(profileId, old);
            }
            return new Commit<>(m.after(), old);
        });
        if (commit == null) {
            return CompletableFuture.completedFuture(Result.CONFLICT);
        }
        return flushOwner.apply(commit.after().ownerUuid())
                .handle((ignored, error) -> error)
                .thenCompose(error -> {
                    if (error != null) {
                        revertCommit(commit, before);
                        return CompletableFuture.completedFuture(Result.COMMIT_FAILED);
                    }
                    CompanionRecord now = index.get(profileId);
                    if (now == null || now.revision() != commit.after().revision()) {
                        return CompletableFuture.completedFuture(Result.CONFLICT);
                    }
                    if (commit.oldBody() != null) {
                        removeOldBody.accept(profileId, commit.oldBody());
                    }
                    return spawnSafely(commit.after(), snapshot, destination, reason)
                            .thenApply(ok -> {
                                if (ok) {
                                    return Result.RESTORED;
                                }
                                index.revert(profileId, commit.after().revision(), before);
                                return Result.SPAWN_FAILED;
                            });
                });
    }

    private CompletableFuture<Boolean> spawnSafely(CompanionRecord committed, SnapshotEnvelope snapshot,
                                                   Destination destination, RestoreRules.Reason reason) {
        CompletableFuture<Boolean> spawned;
        try {
            spawned = spawner.spawn(committed, snapshot, destination, reason);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(false);
        }
        return spawned.handle((ok, error) -> error == null && Boolean.TRUE.equals(ok));
    }

    /** Undoes an unwritten commit and re-registers the old body, unless the record changed since. */
    private void revertCommit(Commit<R> commit, CompanionRecord before) {
        index.atomically(() -> {
            if (index.revert(before.profileId(), commit.after().revision(), before).applied() && commit.oldBody() != null) {
                loaded.put(before.profileId(), commit.oldBody());
            }
            return null;
        });
    }

    private static Result map(RestoreRules.Verdict verdict) {
        return switch (verdict) {
            case NOT_FOUND -> Result.NOT_FOUND;
            case NOT_ALLOWED -> Result.NOT_ALLOWED;
            case NO_SNAPSHOT -> Result.NO_SNAPSHOT;
            case COOLDOWN -> Result.COOLDOWN;
            case ALLOWED -> Result.RESTORED;
        };
    }

    private record Commit<R>(@Nonnull CompanionRecord after, @Nullable R oldBody) {
    }
}

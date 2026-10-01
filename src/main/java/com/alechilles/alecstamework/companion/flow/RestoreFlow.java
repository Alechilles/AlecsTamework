package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.function.LongSupplier;
import java.util.logging.Level;
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
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public enum Result { RESTORED, NOT_FOUND, NOT_ALLOWED, COOLDOWN, NO_SNAPSHOT, CONFLICT, COMMIT_FAILED, SPAWN_FAILED,
        STALE, OWNED_LIMIT, GROUP_LIMIT }

    /**
     * Where to restore. {@code world} is a world name; the production spawner resolves it.
     * {@code yaw} and {@code pitch} are in radians, as {@code Rotation3f} expects.
     */
    public record Destination(@Nonnull String world, double x, double y, double z, float yaw, float pitch) {
        public Destination {
            Objects.requireNonNull(world, "world");
        }

        /** The placement's world, position, yaw and pitch. */
        @Nonnull
        public static Destination of(@Nonnull CompanionSpawnPlacement placement) {
            return new Destination(placement.worldKey(), placement.x(), placement.y(), placement.z(),
                    placement.yawRadians(), placement.pitchRadians());
        }
    }

    /** An owner to set in the restore commit. A null uuid releases the companion unowned and untracked; it keeps its tamed state. */
    public record Owner(@Nullable UUID uuid, @Nullable String name) {
    }

    /**
     * One restore. {@code expectedGeneration} is -1 for any; {@code owner} null keeps the record's
     * owner; {@code summonedUntilMs} is a wall-clock expiry, 0 for none, used only by SUMMON.
     */
    public record Request(@Nonnull UUID profileId, @Nonnull RestoreRules.Reason reason, @Nonnull Destination destination,
                          long expectedGeneration, @Nullable Owner owner, long summonedUntilMs) {
        public Request {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(destination, "destination");
        }

        @Nonnull
        public static Request of(@Nonnull UUID profileId, @Nonnull RestoreRules.Reason reason, @Nonnull Destination destination) {
            return new Request(profileId, reason, destination, -1L, null, 0L);
        }

        @Nonnull
        public Request withGeneration(long generation) {
            return new Request(profileId, reason, destination, generation, owner, summonedUntilMs);
        }

        @Nonnull
        public Request withOwner(@Nonnull Owner newOwner) {
            return new Request(profileId, reason, destination, expectedGeneration, newOwner, summonedUntilMs);
        }

        @Nonnull
        public Request withSummonedUntil(long untilMs) {
            return new Request(profileId, reason, destination, expectedGeneration, owner, untilMs);
        }
    }

    /**
     * Spawns the committed companion at the destination; completes true once the body is added.
     *
     * <p>Contract the flow relies on: completing false or exceptionally means no body of
     * {@code committed}'s generation was added and none will be. No late world task (for example
     * one still queued after a timeout) may add it, because the flow then reverts the record.
     * On the world thread, before adding the body, the spawner re-checks that the record's
     * revision still equals {@code committed.revision()}, and completes false when it does not.
     */
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
     * @param removeOldBody removes the old body from its world; called only after the commit is written,
     *                      or once a newer change replaced the commit and the old body is stale
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
        return restore(Request.of(profileId, reason, destination));
    }

    /** Never completes exceptionally for an expected failure; the {@link Result} says what happened. */
    @Nonnull
    public CompletableFuture<Result> restore(@Nonnull Request request) {
        UUID profileId = request.profileId();
        CompanionRecord before = index.get(profileId);
        RestoreRules.Verdict verdict = RestoreRules.forRecord(before, request.reason(), clock.getAsLong(),
                request.expectedGeneration());
        if (verdict != RestoreRules.Verdict.ALLOWED) {
            return CompletableFuture.completedFuture(map(verdict));
        }
        return snapshots.apply(profileId).handle((snapshot, error) -> error == null ? snapshot : null)
                .thenCompose(snapshot -> commitAndSpawn(before, snapshot, request));
    }

    private CompletableFuture<Result> commitAndSpawn(CompanionRecord before, @Nullable SnapshotEnvelope snapshot,
                                                     Request request) {
        RestoreRules.Reason reason = request.reason();
        Destination destination = request.destination();
        RestoreRules.Verdict snapshotVerdict = RestoreRules.forSnapshot(before, snapshot, reason);
        if (snapshotVerdict != RestoreRules.Verdict.ALLOWED) {
            return CompletableFuture.completedFuture(map(snapshotVerdict));
        }
        UUID profileId = before.profileId();
        UUID newNpcUuid = UUID.randomUUID();
        // The revision check makes a change made while the snapshot was read win.
        Commit<R> commit = index.atomically(() -> {
            CompanionIndex.Mutation m = index.update(profileId, before.revision(), commitChange(before, request, newNpcUuid));
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
        return flushOwners(before, commit.after())
                .handle((ignored, error) -> error)
                .thenCompose(error -> {
                    if (error != null) {
                        if (!revertCommit(commit, before)) {
                            removeStaleOldBody(commit);
                        }
                        return CompletableFuture.completedFuture(Result.COMMIT_FAILED);
                    }
                    CompanionRecord now = index.get(profileId);
                    if (now == null || now.revision() != commit.after().revision()) {
                        removeStaleOldBody(commit);
                        return CompletableFuture.completedFuture(Result.CONFLICT);
                    }
                    removeOldBodySafely(profileId, commit.oldBody());
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

    /** LIVE at the destination, or an unowned tombstone when the request leaves no owner. */
    private static UnaryOperator<CompanionRecord.Builder> commitChange(CompanionRecord before, Request request,
                                                                       UUID newNpcUuid) {
        Destination d = request.destination();
        Owner owner = request.owner();
        if (owner != null && owner.uuid() == null) {
            UnaryOperator<CompanionRecord.Builder> released =
                    CompanionTransitions.released(before, CompanionTransitions.CAUSE_RELEASED_UNOWNED);
            return b -> released.apply(b).ownerUuid(null).ownerName(null);
        }
        UnaryOperator<CompanionRecord.Builder> restored =
                CompanionTransitions.restored(before, d.world(), d.x(), d.y(), d.z(), newNpcUuid);
        return b -> {
            restored.apply(b);
            if (owner != null) {
                b.ownerUuid(owner.uuid()).ownerName(owner.name());
            }
            if (request.reason() == RestoreRules.Reason.SUMMON) {
                b.summonedUntilMs(request.summonedUntilMs());
            }
            return b;
        };
    }

    /** An owner change writes the new owner's file first, then the old one's; either failure fails the commit. */
    private CompletableFuture<Void> flushOwners(CompanionRecord before, CompanionRecord after) {
        CompletableFuture<Void> first = flushOwner.apply(after.ownerUuid());
        if (Objects.equals(before.ownerUuid(), after.ownerUuid())) {
            return first;
        }
        return first.thenCompose(v -> flushOwner.apply(before.ownerUuid()));
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

    /**
     * Undoes an unwritten commit and re-registers the old body. Returns false, changing nothing,
     * when the record changed since the commit.
     */
    private boolean revertCommit(Commit<R> commit, CompanionRecord before) {
        return index.atomically(() -> {
            if (!index.revert(before.profileId(), commit.after().revision(), before).applied()) {
                return false;
            }
            if (commit.oldBody() != null) {
                loaded.put(before.profileId(), commit.oldBody());
            }
            return true;
        });
    }

    /**
     * A newer change replaced the commit, so it builds on generation+1 and the old body, already
     * unregistered at commit, is stale under the fence. Remove it rather than leave it untracked.
     */
    private void removeStaleOldBody(Commit<R> commit) {
        removeOldBodySafely(commit.after().profileId(), commit.oldBody());
    }

    /** A failed removal is logged and never stops the spawn, so the committed record gets a body. */
    private void removeOldBodySafely(UUID profileId, @Nullable R oldBody) {
        if (oldBody == null) {
            return;
        }
        try {
            removeOldBody.accept(profileId, oldBody);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Could not remove the old body of restored companion %s", profileId);
        }
    }

    private static Result map(RestoreRules.Verdict verdict) {
        return switch (verdict) {
            case NOT_FOUND -> Result.NOT_FOUND;
            case NOT_ALLOWED -> Result.NOT_ALLOWED;
            case NO_SNAPSHOT -> Result.NO_SNAPSHOT;
            case COOLDOWN -> Result.COOLDOWN;
            case STALE -> Result.STALE;
            case ALLOWED -> Result.RESTORED;
        };
    }

    private record Commit<R>(@Nonnull CompanionRecord after, @Nullable R oldBody) {
    }
}

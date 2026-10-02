package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.admission.ProviderAdmission;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
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
 * <p>A managed role's admission provider is asked after the snapshot read and before the commit
 * (plan 6 R10). Its claims and domain limits are checked again with the built-in caps under the
 * index lock, and the claims are stored on the committed record.
 *
 * <p>Continuations run on whichever thread completes the snapshot read, the provider's decision,
 * the flush or the spawn (usually the writer thread). {@code removeOldBody} and the {@link Spawner} must therefore hand
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

    /**
     * {@code PROVIDER_DENIED}: an admission provider denied the restore or one of its domain limits
     * is reached. {@code PROVIDER_UNAVAILABLE}: the provider gave no decision. Nothing changed.
     */
    public enum Result { RESTORED, NOT_FOUND, NOT_ALLOWED, COOLDOWN, NO_SNAPSHOT, CONFLICT, COMMIT_FAILED, SPAWN_FAILED,
        STALE, OWNED_LIMIT, GROUP_LIMIT, PROVIDER_DENIED, PROVIDER_UNAVAILABLE }

    /**
     * A restore's result with the translation key of a population refusal (a cap, a provider's
     * denial or a domain limit); {@code messageKey} is null for every other result.
     */
    public record Outcome(@Nonnull Result result, @Nullable String messageKey) {
    }

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
     * owner; {@code summonedUntilMs} is the wall-clock time the restored companion's summon timer runs out, 0 for none.
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
     * {@code snapshot} is null only for a provisioned bonded companion that never had a snapshot
     * written ({@link RestoreRules#respawnsFromRole}); its body is built from the committed
     * record's role.
     *
     * <p>Contract the flow relies on: completing false or exceptionally means no body of
     * {@code committed}'s generation was added and none will be. No late world task (for example
     * one still queued after a timeout) may add it, because the flow then reverts the record.
     * On the world thread, before adding the body, the spawner re-checks that the record is still
     * the committed one ({@link #sameHolder}), and completes false when it is not.
     */
    public interface Spawner {
        @Nonnull
        CompletableFuture<Boolean> spawn(@Nonnull CompanionRecord committed, @Nullable SnapshotEnvelope snapshot,
                                         @Nonnull Destination destination, @Nonnull RestoreRules.Reason reason);
    }

    private final CompanionIndex index;
    private final LoadedBodies<R> loaded;
    private final Function<UUID, CompletableFuture<SnapshotEnvelope>> snapshots;
    private final Function<UUID, CompletableFuture<Void>> flushOwner;
    private final Spawner spawner;
    private final BiConsumer<UUID, R> removeOldBody;
    private final LongSupplier clock;
    private final ProviderAdmission providers;
    private final CompanionAdmissionGate.Check admission;

    /** A flow with no admission providers; {@code admission} is the built-in caps only. */
    public RestoreFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                       @Nonnull Function<UUID, CompletableFuture<SnapshotEnvelope>> snapshots,
                       @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner, @Nonnull Spawner spawner,
                       @Nonnull BiConsumer<UUID, R> removeOldBody, @Nonnull LongSupplier clock,
                       @Nonnull BiFunction<CompanionRecord, CompanionRecord, CompanionAdmission.Refusal> admission) {
        this(index, loaded, snapshots, flushOwner, spawner, removeOldBody, clock, ProviderAdmission.none(),
                builtInOnly(admission));
    }

    /**
     * @param snapshots     reads a profile's snapshot off the world thread; completes with null when there is none
     * @param flushOwner    writes the given owner's file now (null for unowned); fails on error or timeout
     * @param removeOldBody removes the old body from its world; called only after the commit is written,
     *                      or once a newer change replaced the commit and the old body is stale
     * @param clock         wall clock, used for the revive cooldown
     * @param providers     the admission provider stage, asked before the index lock is taken
     * @param admission     population caps and provider domain limits for the change from the
     *                      current record to the committed one; returns null to admit. Called
     *                      under the index lock.
     */
    public RestoreFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                       @Nonnull Function<UUID, CompletableFuture<SnapshotEnvelope>> snapshots,
                       @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner, @Nonnull Spawner spawner,
                       @Nonnull BiConsumer<UUID, R> removeOldBody, @Nonnull LongSupplier clock,
                       @Nonnull ProviderAdmission providers, @Nonnull CompanionAdmissionGate.Check admission) {
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.flushOwner = Objects.requireNonNull(flushOwner, "flushOwner");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.removeOldBody = Objects.requireNonNull(removeOldBody, "removeOldBody");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.admission = Objects.requireNonNull(admission, "admission");
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
        return restoreOutcome(request).thenApply(Outcome::result);
    }

    /** As {@link #restore(Request)}, with the message key of a population refusal. */
    @Nonnull
    public CompletableFuture<Outcome> restoreOutcome(@Nonnull Request request) {
        UUID profileId = request.profileId();
        CompanionRecord before = index.get(profileId);
        RestoreRules.Verdict verdict = RestoreRules.forRecord(before, request.reason(), clock.getAsLong(),
                request.expectedGeneration());
        if (verdict != RestoreRules.Verdict.ALLOWED) {
            return done(map(verdict));
        }
        return snapshots.apply(profileId).handle((snapshot, error) -> {
            // No snapshot is "never written" only when the read itself worked and no body is
            // loaded: a failed read, or a loaded body whose capture failed, still has state to lose.
            boolean neverWritten = error == null && snapshot == null && loaded.get(profileId) == null;
            return admitAndCommit(before, error == null ? snapshot : null, neverWritten, request);
        }).thenCompose(outcome -> outcome);
    }

    /** Asks the admission provider, off the index lock, then commits with what it allowed. */
    private CompletableFuture<Outcome> admitAndCommit(CompanionRecord before, @Nullable SnapshotEnvelope snapshot,
                                                      boolean neverWritten, Request request) {
        RestoreRules.Verdict snapshotVerdict =
                RestoreRules.forSnapshot(before, snapshot, request.reason(), neverWritten);
        if (snapshotVerdict != RestoreRules.Verdict.ALLOWED) {
            return done(map(snapshotVerdict));
        }
        UnaryOperator<CompanionRecord.Builder> change = commitChange(before, request, UUID.randomUUID());
        return providers.evaluate(before, change.apply(before.toBuilder()).build()).toCompletableFuture()
                .thenCompose(provider -> {
                    if (!provider.admitted()) {
                        return CompletableFuture.completedFuture(
                                new Outcome(map(provider.refusal()), provider.messageKey()));
                    }
                    CompanionAdmission.Provided provided = provider.provided();
                    UnaryOperator<CompanionRecord.Builder> withClaims = !provider.asked() ? change
                            : b -> change.apply(b).domainClaims(provided.claims());
                    return commitAndSpawn(before, snapshot, request, withClaims, provided);
                });
    }

    private CompletableFuture<Outcome> commitAndSpawn(CompanionRecord before, SnapshotEnvelope snapshot,
                                                      Request request, UnaryOperator<CompanionRecord.Builder> change,
                                                      CompanionAdmission.Provided provided) {
        RestoreRules.Reason reason = request.reason();
        Destination destination = request.destination();
        UUID profileId = before.profileId();
        CompanionAdmissionGate.Denial[] refused = new CompanionAdmissionGate.Denial[1];
        // The revision check makes a change made while the snapshot was read win, before the caps
        // are checked. The caps are checked in the same locked step, so no other change can fill
        // the slot in between.
        Commit<R> commit = index.atomically(() -> {
            CompanionRecord current = index.get(profileId);
            if (current == null || current.revision() != before.revision()) {
                return null;
            }
            refused[0] = admission.deny(before, change.apply(before.toBuilder()).build(), provided);
            if (refused[0] != null) {
                return null;
            }
            CompanionIndex.Mutation m = index.update(profileId, before.revision(), change);
            if (!m.applied()) {
                return null;
            }
            R old = loaded.get(profileId);
            if (old != null) {
                loaded.removeIfSame(profileId, old);
            }
            return new Commit<>(m.after(), old);
        });
        if (refused[0] != null) {
            return CompletableFuture.completedFuture(
                    new Outcome(map(refused[0].refusal()), refused[0].messageKey()));
        }
        if (commit == null) {
            return done(Result.CONFLICT);
        }
        return OwnerFileFlush.flushOwners(flushOwner, before, commit.after())
                .handle((ignored, error) -> error)
                .thenCompose(error -> {
                    if (error != null) {
                        if (!revertCommit(commit, before)) {
                            removeStaleOldBody(commit);
                        }
                        return done(Result.COMMIT_FAILED);
                    }
                    if (!sameHolder(commit.after(), index.get(profileId))) {
                        removeStaleOldBody(commit);
                        return done(Result.CONFLICT);
                    }
                    removeOldBodySafely(profileId, commit.oldBody());
                    return spawnSafely(commit.after(), snapshot, destination, reason)
                            .thenApply(ok -> {
                                if (ok) {
                                    return new Outcome(Result.RESTORED, null);
                                }
                                if (!revertHolder(index, commit.after(), before)) {
                                    LOGGER.at(Level.WARNING).log("Companion %s changed while its spawn failed; "
                                            + "the newer change stands", profileId);
                                }
                                return new Outcome(Result.SPAWN_FAILED, null);
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
            // The restored transition clears the timer. A request that names none keeps the timer
            // of a body that was out (a recall, or a recover of a LIVE record), so a timed summon
            // that follows its owner stays timed; from any other location 0 leaves it untimed.
            long until = request.summonedUntilMs() != 0L ? request.summonedUntilMs()
                    : before.location().kind() == LocationKind.LIVE ? before.summonedUntilMs() : 0L;
            b.summonedUntilMs(until);
            return b;
        };
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
            if (!revertHolder(index, commit.after(), before)) {
                return false;
            }
            if (commit.oldBody() != null) {
                loaded.put(before.profileId(), commit.oldBody());
            }
            return true;
        });
    }

    /**
     * Whether {@code now} is still the record a flow committed as {@code committed}: the same
     * generation, location kind and NPC UUID, which are what a restore or a store owns. The
     * record revision is not compared, because a change the flow does not own (an extension
     * write, a rename) raises it and must not strand the committed companion without a body.
     */
    static boolean sameHolder(@Nonnull CompanionRecord committed, @Nullable CompanionRecord now) {
        return now != null && now.generation() == committed.generation()
                && now.location().kind() == committed.location().kind()
                && Objects.equals(now.currentNpcUuid(), committed.currentNpcUuid());
    }

    /**
     * Undoes a flow's commit while the record is still {@link #sameHolder the committed one}:
     * {@code before} comes back, with the extension entries the record has now, since those are
     * not the flow's to undo. Returns false, changing nothing, when a newer change holds the record.
     */
    static boolean revertHolder(@Nonnull CompanionIndex index, @Nonnull CompanionRecord committed,
                                @Nonnull CompanionRecord before) {
        return index.atomically(() -> {
            CompanionRecord now = index.get(committed.profileId());
            return sameHolder(committed, now) && index.revert(committed.profileId(), now.revision(),
                    before.toBuilder().extensions(now.extensions()).build()).applied();
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

    private static CompletableFuture<Outcome> done(Result result) {
        return CompletableFuture.completedFuture(new Outcome(result, null));
    }

    private static CompanionAdmissionGate.Check builtInOnly(
            BiFunction<CompanionRecord, CompanionRecord, CompanionAdmission.Refusal> admission) {
        Objects.requireNonNull(admission, "admission");
        return (before, after, provided) -> {
            CompanionAdmission.Refusal refusal = admission.apply(before, after);
            return refusal == null ? null : CompanionAdmissionGate.Denial.of(refusal);
        };
    }

    private static Result map(CompanionAdmission.Refusal refusal) {
        return switch (refusal) {
            case OWNED -> Result.OWNED_LIMIT;
            case GROUP_OWNED, GROUP_DEPLOYED -> Result.GROUP_LIMIT;
            case PROVIDER_DENIED -> Result.PROVIDER_DENIED;
            case PROVIDER_UNAVAILABLE -> Result.PROVIDER_UNAVAILABLE;
        };
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

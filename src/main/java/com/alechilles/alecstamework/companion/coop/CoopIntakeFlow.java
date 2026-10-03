package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Commit-first coop intake (spec 8.9), following {@code CaptureFlow}. A stamped body or a capture
 * item moves its record to COOP with generation+1; the slot entry is written, and the body removed
 * or the item consumed, only after the commit is written. An unowned, unstamped body is stored
 * inline in its slot entry and never touches the index.
 *
 * <p>A flush failure reverts the record and re-registers the body. A slot write that fails after
 * the commit reverts the same way, so the companion stays where it was. When a newer change
 * replaced the commit, the body is stale under the generation fence and is removed.
 *
 * <p>Each slot is reserved for the length of one intake, so two intakes never pick the same slot.
 * Continuations run on whichever thread completes the flush or the slot write; {@link Coop} hands
 * its world work to the coop's world thread itself. Results never complete exceptionally.
 *
 * @param <R> the body reference type (Ref&lt;EntityStore&gt; in production)
 */
public final class CoopIntakeFlow<R> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public enum Result {
        /** The slot holds the resident; the body is removed or the item consumed. */
        TAKEN_IN,
        /** The record is not LIVE at the body's stamp, not ITEM at the item's generation, or another body holds it. */
        NOT_ELIGIBLE,
        /** Another intake holds the slot. Nothing changed. */
        BUSY,
        /** A newer change won. The record keeps it; a stale body is removed. */
        CONFLICT,
        /** The commit was not written and was undone. */
        COMMIT_FAILED,
        /** The slot could not be written; the commit was undone (or a newer change kept). */
        SLOT_FAILED
    }

    /** The coop block and slot a resident goes into. {@code coopId} is for effects only. */
    public record Site(@Nonnull String world, int x, int y, int z, int slot, @Nullable String coopId) {
        public Site {
            Objects.requireNonNull(world, "world");
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative");
            }
        }
    }

    /** The live side of a coop. Implementations run their world work on the coop's world thread. */
    public interface Coop<R> {
        /**
         * Writes {@code entry} into the coop block and marks its chunk for saving, refusing when
         * the block is gone or the slot is held by a current entry. Completes true when written.
         */
        @Nonnull
        CompletableFuture<Boolean> writeSlot(@Nonnull Site site, @Nonnull TameworkCoopSlotsComponent.Slot entry);

        /**
         * Takes an unowned body in within one world task: re-checks that it is still valid,
         * unowned, unstamped and without a record, then removes it and writes {@code entry}.
         * Completes true when done; false changes nothing.
         */
        @Nonnull
        CompletableFuture<Boolean> takeInUnowned(@Nonnull Site site, @Nonnull TameworkCoopSlotsComponent.Slot entry,
                                                 @Nonnull R body);

        /** Removes the body from its world. Its registry entry is already gone. */
        void removeBody(@Nonnull R body);
    }

    /**
     * A stamped live body, snapshotted on its world thread just before. {@code summary} is null
     * when it could not be read; the record then keeps its summary.
     */
    public record LiveIntake<R>(@Nonnull UUID profileId, long stampedGeneration, @Nonnull R body,
                                @Nonnull BsonDocument snapshotData, @Nullable CompanionSummary summary,
                                @Nonnull Site site) {
        public LiveIntake {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(snapshotData, "snapshotData");
            Objects.requireNonNull(site, "site");
        }
    }

    /** A capture item; {@code consumeItem} removes the exact stack once the slot is written. */
    public record ItemIntake(@Nonnull UUID profileId, long itemGeneration, @Nonnull Site site,
                             @Nonnull Runnable consumeItem) {
        public ItemIntake {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(consumeItem, "consumeItem");
        }
    }

    private final CompanionIndex index;
    private final LoadedBodies<R> loaded;
    private final BiConsumer<UUID, SnapshotEnvelope> queueSnapshot;
    private final Function<UUID, CompletableFuture<Void>> flushOwner;
    private final Coop<R> coop;
    private final Set<SlotKey> reservedSlots = ConcurrentHashMap.newKeySet();

    /**
     * @param queueSnapshot queues a snapshot write for a profile
     * @param flushOwner    writes the given owner's file now (null for unowned); fails on error or timeout
     */
    public CoopIntakeFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                          @Nonnull BiConsumer<UUID, SnapshotEnvelope> queueSnapshot,
                          @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner, @Nonnull Coop<R> coop) {
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.flushOwner = Objects.requireNonNull(flushOwner, "flushOwner");
        this.coop = Objects.requireNonNull(coop, "coop");
    }

    /** True while an intake into this exact slot is in flight. */
    public boolean reserved(@Nonnull String world, int x, int y, int z, int slot) {
        return reservedSlots.contains(new SlotKey(world, x, y, z, slot));
    }

    /** Takes in a stamped live body. */
    @Nonnull
    public CompletableFuture<Result> intakeLive(@Nonnull LiveIntake<R> intake) {
        return reserved(intake.site(), () -> live(intake));
    }

    /** Takes in a capture item whose generation must equal its ITEM record's. */
    @Nonnull
    public CompletableFuture<Result> intakeItem(@Nonnull ItemIntake intake) {
        return reserved(intake.site(), () -> item(intake));
    }

    /**
     * Takes in an unowned, unstamped body: its entity document goes into the slot entry and the
     * body is removed in the same world task. No record is made, so it comes back unstamped and
     * keeps its death drops.
     */
    @Nonnull
    public CompletableFuture<Result> intakeUnowned(@Nonnull R body, @Nonnull BsonDocument entity, @Nonnull Site site) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(entity, "entity");
        TameworkCoopSlotsComponent.Slot entry = TameworkCoopSlotsComponent.Slot.unowned(site.slot(), entity);
        return reserved(site, () -> completesFalseOnFailure(() -> coop.takeInUnowned(site, entry, body), site)
                .thenApply(done -> done ? Result.TAKEN_IN : Result.SLOT_FAILED));
    }

    private CompletableFuture<Result> reserved(Site site, Supplier<CompletableFuture<Result>> intake) {
        SlotKey key = new SlotKey(site.world(), site.x(), site.y(), site.z(), site.slot());
        if (!reservedSlots.add(key)) {
            return CompletableFuture.completedFuture(Result.BUSY);
        }
        CompletableFuture<Result> started;
        try {
            started = intake.get();
        } catch (RuntimeException failure) {
            started = CompletableFuture.failedFuture(failure);
        }
        return started.handle((result, error) -> {
            reservedSlots.remove(key);
            if (error != null) {
                LOGGER.at(Level.WARNING).withCause(error).log("Coop intake into slot %s failed", site);
                return Result.COMMIT_FAILED;
            }
            return result;
        });
    }

    private CompletableFuture<Result> live(LiveIntake<R> intake) {
        UUID profileId = intake.profileId();
        Site site = intake.site();
        Commit<R> commit = index.atomically(() -> {
            CompanionRecord before = index.get(profileId);
            if (before == null || before.location().kind() != LocationKind.LIVE
                    || before.generation() != intake.stampedGeneration()) {
                return Commit.refused(Result.NOT_ELIGIBLE);
            }
            R registered = loaded.get(profileId);
            if (registered != null && !registered.equals(intake.body())) {
                // Another body holds this profile; taking this one in would strand the registered one.
                return Commit.refused(Result.NOT_ELIGIBLE);
            }
            CompanionIndex.Mutation m = index.update(profileId, before.revision(), CompanionTransitions.coopIntake(
                    before, site.world(), site.x(), site.y(), site.z(), site.slot(), intake.summary()));
            if (!m.applied()) {
                return Commit.refused(Result.CONFLICT);
            }
            Commit<R> applied = new Commit<>(null, before, m.after(), intake.body(), intake.snapshotData(),
                    loaded.removeIfSame(profileId, intake.body()));
            // Queued under the index lock with the commit, so no flush can write the record's owner
            // file before its snapshot (the CompanionWriter.queueSnapshot contract).
            try {
                queueSnapshot.accept(profileId, new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT,
                        m.after().generation(), intake.snapshotData()));
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure)
                        .log("Could not queue the snapshot of companion %s for its coop; the intake is undone", profileId);
                // Still under the lock, so the record is exactly the commit and the undo applies.
                undo(applied);
                return Commit.refused(Result.COMMIT_FAILED);
            }
            return applied;
        });
        if (commit.refusal() != null) {
            return CompletableFuture.completedFuture(commit.refusal());
        }
        return afterCommit(commit, site, () -> removeBodySafely(intake.body()));
    }

    private CompletableFuture<Result> item(ItemIntake intake) {
        UUID profileId = intake.profileId();
        Site site = intake.site();
        Commit<R> commit = index.atomically(() -> {
            CompanionRecord before = index.get(profileId);
            if (before == null || before.location().kind() != LocationKind.ITEM
                    || before.generation() != intake.itemGeneration()) {
                return Commit.refused(Result.NOT_ELIGIBLE);
            }
            CompanionIndex.Mutation m = index.update(profileId, before.revision(), CompanionTransitions.coopIntake(
                    before, site.world(), site.x(), site.y(), site.z(), site.slot(), null));
            return m.applied() ? new Commit<R>(null, before, m.after(), null, null, false)
                    : Commit.<R>refused(Result.CONFLICT);
        });
        if (commit.refusal() != null) {
            return CompletableFuture.completedFuture(commit.refusal());
        }
        return afterCommit(commit, site, () -> {
            try {
                intake.consumeItem().run();
            } catch (RuntimeException failure) {
                // The companion is in the coop; the item left behind is stale by generation.
                LOGGER.at(Level.WARNING).withCause(failure)
                        .log("Could not consume the capture item of companion %s after its coop intake", profileId);
            }
        });
    }

    /** Flushes, re-checks, writes the slot and then runs {@code finish} (body removal or item consumption). */
    private CompletableFuture<Result> afterCommit(Commit<R> commit, Site site, Runnable finish) {
        CompanionRecord after = commit.after();
        UUID profileId = after.profileId();
        return flush(after.ownerUuid()).handle((ignored, error) -> error).thenCompose(error -> {
            if (error != null) {
                LOGGER.at(Level.WARNING).withCause(error)
                        .log("Coop intake of companion %s was not written; the intake is undone", profileId);
                if (!undo(commit)) {
                    removeStaleBody(commit);
                }
                return CompletableFuture.completedFuture(Result.COMMIT_FAILED);
            }
            // A change that kept the coop holder (a rename, say) does not undo the intake.
            if (!stillInCoop(after)) {
                removeStaleBody(commit);
                return CompletableFuture.completedFuture(Result.CONFLICT);
            }
            // No production watermark here: the coop stamps the intake time when it writes the entry.
            return writeSlot(site, TameworkCoopSlotsComponent.Slot.companion(site.slot(), profileId, after.generation()))
                    .thenApply(written -> {
                        if (written) {
                            finish.run();
                            return Result.TAKEN_IN;
                        }
                        LOGGER.at(Level.WARNING).log("Could not write the coop slot of companion %s; the intake is undone",
                                profileId);
                        if (!undo(commit)) {
                            removeStaleBody(commit);
                        }
                        return Result.SLOT_FAILED;
                    });
        });
    }

    private boolean stillInCoop(CompanionRecord after) {
        CompanionRecord now = index.get(after.profileId());
        return now != null && now.location().kind() == LocationKind.COOP && now.generation() == after.generation();
    }

    private CompletableFuture<Void> flush(@Nullable UUID owner) {
        try {
            return flushOwner.apply(owner);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<Boolean> writeSlot(Site site, TameworkCoopSlotsComponent.Slot entry) {
        return completesFalseOnFailure(() -> coop.writeSlot(site, entry), site);
    }

    /** A failed or refused write completes false, never exceptionally. */
    private static CompletableFuture<Boolean> completesFalseOnFailure(Supplier<CompletableFuture<Boolean>> write,
                                                                      Site site) {
        CompletableFuture<Boolean> written;
        try {
            written = write.get();
        } catch (RuntimeException e) {
            written = CompletableFuture.failedFuture(e);
        }
        return written.handle((ok, error) -> {
            if (error != null) {
                LOGGER.at(Level.WARNING).withCause(error).log("Coop slot write into %s failed", site);
                return false;
            }
            return Boolean.TRUE.equals(ok);
        });
    }

    /**
     * Undoes the commit when the record still is exactly the committed one: the record goes back,
     * a body unregistered at commit is registered again, and a fresh snapshot is queued again at
     * the old generation (a snapshot newer than its record cannot be restored). Returns false,
     * changing nothing, when the record changed since the commit.
     */
    private boolean undo(Commit<R> commit) {
        CompanionRecord before = commit.before();
        UUID profileId = before.profileId();
        try {
            return index.atomically(() -> {
                if (!index.revert(profileId, commit.after().revision(), before).applied()) {
                    return false;
                }
                if (commit.unregistered()) {
                    loaded.put(profileId, commit.body());
                }
                if (commit.snapshotData() != null) {
                    try {
                        queueSnapshot.accept(profileId, new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT,
                                before.generation(), commit.snapshotData()));
                    } catch (RuntimeException failure) {
                        LOGGER.at(Level.WARNING).withCause(failure)
                                .log("Could not re-queue the snapshot of companion %s after an undone coop intake",
                                        profileId);
                    }
                }
                return true;
            });
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not undo the coop intake of companion %s", profileId);
            return false;
        }
    }

    /** The body is unregistered and older than its record; the fence would remove it on its next load anyway. */
    private void removeStaleBody(Commit<R> commit) {
        if (commit.body() != null) {
            removeBodySafely(commit.body());
        }
    }

    private void removeBodySafely(R body) {
        try {
            coop.removeBody(body);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not remove a body taken into a coop");
        }
    }

    private record SlotKey(String world, int x, int y, int z, int slot) {
    }

    private record Commit<R>(@Nullable Result refusal, @Nullable CompanionRecord before,
                             @Nullable CompanionRecord after, @Nullable R body,
                             @Nullable BsonDocument snapshotData, boolean unregistered) {
        static <R> Commit<R> refused(Result refusal) {
            return new Commit<>(refusal, null, null, null, null, false);
        }
    }
}

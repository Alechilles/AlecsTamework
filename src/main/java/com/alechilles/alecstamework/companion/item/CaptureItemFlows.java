package com.alechilles.alecstamework.companion.item;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ways out for a companion held by a capture item Tamework cannot see (spec 8.14). Forget makes
 * the record a RELEASED tombstone; Recall restores it from its snapshot; a destroyed item leaves
 * the companion LOST, to be recovered by its owner. Each raises the generation, so any surviving
 * copy of the item is stale and empty on use. After a successful Forget or Recall the item sweep
 * is asked to empty the copies the record's owner holds and the copy at the item's last known
 * place, so they do not keep their filled look until used; copies elsewhere still empty on use.
 *
 * <p>{@link #forget} and {@link #itemDestroyed} only change the in-memory index and are safe from
 * any thread. Forget queues the snapshot delete after the tombstone, as in the release flow; a
 * destroyed item keeps the snapshot, which Recover restores from.</p>
 */
public final class CaptureItemFlows {
    public enum Result { FORGOTTEN, NOT_FOUND, NOT_OWNER, NOT_IN_ITEM }

    private final CompanionIndex index;
    private final Consumer<UUID> deleteSnapshot;
    private final RestoreFlow<?> restoreFlow;
    /** Empties the visible copies of an item whose companion left it. Must be safe from any thread. */
    @FunctionalInterface
    public interface ItemSweep {
        /**
         * @param owner the record's owner, or null for an unowned companion
         * @param itemGeneration the generation the item was made at, one older than the record now
         */
        void sweep(@Nullable UUID owner, @Nonnull UUID profileId, long itemGeneration);
    }

    private volatile ItemSweep itemSweep = (owner, profileId, itemGeneration) -> { };

    public CaptureItemFlows(@Nonnull CompanionIndex index, @Nonnull Consumer<UUID> deleteSnapshot,
                            @Nonnull RestoreFlow<?> restoreFlow) {
        this.index = Objects.requireNonNull(index, "index");
        this.deleteSnapshot = Objects.requireNonNull(deleteSnapshot, "deleteSnapshot");
        this.restoreFlow = Objects.requireNonNull(restoreFlow, "restoreFlow");
    }

    /** Sets what empties the item's visible copies after a Forget or Recall. */
    public void useItemSweep(@Nonnull ItemSweep sweep) {
        itemSweep = Objects.requireNonNull(sweep, "sweep");
    }

    /** @param actingOwner the owner forgetting it, or null for an admin. */
    @Nonnull
    public Result forget(@Nonnull UUID profileId, @Nullable UUID actingOwner) {
        UUID[] owner = new UUID[1];
        long[] itemGeneration = new long[1];
        Result result = index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            if (record == null) {
                return Result.NOT_FOUND;
            }
            owner[0] = record.ownerUuid();
            itemGeneration[0] = record.generation();
            if (actingOwner != null && !actingOwner.equals(record.ownerUuid())) {
                return Result.NOT_OWNER;
            }
            if (record.location().kind() != LocationKind.ITEM) {
                return Result.NOT_IN_ITEM;
            }
            return index.update(profileId, record.revision(),
                    CompanionTransitions.released(record, CompanionTransitions.CAUSE_FORGOTTEN)).applied()
                    ? Result.FORGOTTEN : Result.NOT_IN_ITEM;
        });
        if (result == Result.FORGOTTEN) {
            deleteSnapshot.accept(profileId);
            sweepItems(owner[0], profileId, itemGeneration[0]);
        }
        return result;
    }

    /**
     * Restores the companion at {@code destination} for the RECOVER reason, which allows a record in
     * an item, a coop or lost. Callers check ownership first.
     */
    @Nonnull
    public CompletableFuture<RestoreFlow.Result> recall(@Nonnull UUID profileId,
                                                        @Nonnull RestoreFlow.Destination destination) {
        CompanionRecord before = index.get(profileId);
        CompletableFuture<RestoreFlow.Result> restored =
                restoreFlow.restore(RestoreFlow.Request.of(profileId, RestoreRules.Reason.RECOVER, destination));
        if (before == null || before.location().kind() != LocationKind.ITEM) {
            return restored;
        }
        UUID owner = before.ownerUuid();
        long itemGeneration = before.generation();
        return restored.whenComplete((result, error) -> {
            if (result == RestoreFlow.Result.RESTORED) {
                sweepItems(owner, profileId, itemGeneration);
            }
        });
    }

    /** The sweep is presentation only: its failure never changes the result of the flow. */
    private void sweepItems(@Nullable UUID owner, UUID profileId, long itemGeneration) {
        try {
            itemSweep.sweep(owner, profileId, itemGeneration);
        } catch (RuntimeException ignored) {
            // The item still turns empty on use.
        }
    }

    /**
     * A dropped capture item despawned or fell out of the world. The record becomes LOST with cause
     * ITEM_DESTROYED, one generation newer, only while it is still in an item at the item's
     * generation; a stale or duplicated copy changes nothing. The owner and the snapshot are kept,
     * so the companion still counts for its owner, who can Recover it.
     *
     * @return true when the record became LOST
     */
    public boolean itemDestroyed(@Nonnull CaptureItemKeys.Ref item) {
        UUID profileId = item.profileId();
        return index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            return record != null && record.location().kind() == LocationKind.ITEM
                    && record.generation() == item.generation()
                    && index.update(profileId, record.revision(), CompanionTransitions.lost(record, null,
                    CompanionTransitions.CAUSE_ITEM_DESTROYED, null)).applied();
        });
    }
}

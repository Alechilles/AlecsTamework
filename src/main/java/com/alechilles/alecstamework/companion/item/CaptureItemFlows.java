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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ways out for a companion held by a capture item Tamework cannot see (spec 8.14). Forget and a
 * destroyed item make the record a RELEASED tombstone; Recall restores it from its snapshot. Each
 * raises the generation, so any surviving copy of the item is stale and empty on use. After a
 * successful Forget or Recall the held-item sweep is asked to empty the copies the record's owner
 * holds, so they do not keep their filled look until used; copies elsewhere still empty on use.
 *
 * <p>{@link #forget} and {@link #itemDestroyed} only change the in-memory index and are safe from
 * any thread. The snapshot delete is queued after the tombstone, as in the release flow.</p>
 */
public final class CaptureItemFlows {
    public enum Result { FORGOTTEN, NOT_FOUND, NOT_OWNER, NOT_IN_ITEM }

    private final CompanionIndex index;
    private final Consumer<UUID> deleteSnapshot;
    private final RestoreFlow<?> restoreFlow;
    /** (owner, profile): empties that player's held copies of the item. Must be safe from any thread. */
    private volatile BiConsumer<UUID, UUID> heldItemSweep = (owner, profileId) -> { };

    public CaptureItemFlows(@Nonnull CompanionIndex index, @Nonnull Consumer<UUID> deleteSnapshot,
                            @Nonnull RestoreFlow<?> restoreFlow) {
        this.index = Objects.requireNonNull(index, "index");
        this.deleteSnapshot = Objects.requireNonNull(deleteSnapshot, "deleteSnapshot");
        this.restoreFlow = Objects.requireNonNull(restoreFlow, "restoreFlow");
    }

    /** Sets what empties an online owner's held copies after a Forget or Recall. */
    public void useHeldItemSweep(@Nonnull BiConsumer<UUID, UUID> sweep) {
        heldItemSweep = Objects.requireNonNull(sweep, "sweep");
    }

    /** @param actingOwner the owner forgetting it, or null for an admin. */
    @Nonnull
    public Result forget(@Nonnull UUID profileId, @Nullable UUID actingOwner) {
        UUID[] owner = new UUID[1];
        Result result = index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            if (record == null) {
                return Result.NOT_FOUND;
            }
            owner[0] = record.ownerUuid();
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
            sweepHeldItems(owner[0], profileId);
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
        return restored.whenComplete((result, error) -> {
            if (result == RestoreFlow.Result.RESTORED) {
                sweepHeldItems(owner, profileId);
            }
        });
    }

    /** The sweep is presentation only: its failure never changes the result of the flow. */
    private void sweepHeldItems(@Nullable UUID owner, UUID profileId) {
        if (owner == null) {
            return;
        }
        try {
            heldItemSweep.accept(owner, profileId);
        } catch (RuntimeException ignored) {
            // The item still turns empty on use.
        }
    }

    /**
     * A dropped capture item despawned or fell out of the world. Tombstones the record only while it
     * is still in an item at the item's generation; a stale or duplicated copy changes nothing.
     *
     * @return true when the record became a tombstone
     */
    public boolean itemDestroyed(@Nonnull CaptureItemKeys.Ref item) {
        UUID profileId = item.profileId();
        boolean released = index.atomically(() -> {
            CompanionRecord record = index.get(profileId);
            return record != null && record.location().kind() == LocationKind.ITEM
                    && record.generation() == item.generation()
                    && index.update(profileId, record.revision(),
                    CompanionTransitions.released(record, CompanionTransitions.CAUSE_ITEM_DESTROYED)).applied();
        });
        if (released) {
            deleteSnapshot.accept(profileId);
        }
        return released;
    }
}

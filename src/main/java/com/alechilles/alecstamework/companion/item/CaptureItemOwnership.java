package com.alechilles.alecstamework.companion.item;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.settings.CaptureItemOwnershipMode;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Spec 8.14: who owns a companion held by a capture item. Pure decisions for the server's
 * {@link CaptureItemOwnershipMode}: the owner at capture, whether the item entering a player's
 * inventory moves the owner, whether a player may pick the item up, and the owner at release.
 * A captured companion keeps an owner in every mode; only a wild body caught by an item that
 * does not tame is an unowned capture.
 */
public final class CaptureItemOwnership {
    public enum Decision { IGNORE, STALE, ALREADY_OWNED, TRANSFER, REFUSE }

    /** What the pickup filter does with a capture item entering a player's inventory. */
    public enum Pickup {
        /** The item moves nothing or the mode does not block: let it in. */
        ALLOW,
        /** {@code FOLLOWS_ITEM}: let it in only when the player's limits admit the companion. */
        CHECK_LIMITS,
        /** {@code OWNER_ONLY}: the item belongs to someone else. */
        REFUSE_NOT_OWNER
    }

    /** What a release from a capture item does with the owner. */
    public enum Release {
        /** An unowned wild capture: released unowned. */
        UNOWNED,
        /** The owner releases it: no change. */
        KEEP_OWNER,
        /** The releasing player becomes the owner, subject to admission. */
        ASSIGN_RELEASER,
        /** {@code OWNER_ONLY}: someone else owns it; nothing changes. */
        REFUSE_NOT_OWNER
    }

    private CaptureItemOwnership() {
    }

    /**
     * @param record  the profile's record, or null
     * @param itemGen the generation on the item
     * @param holder  the player now holding the item
     * @param refusal the admission result for {@code holder} taking the record, or null when allowed
     */
    @Nonnull
    public static Decision decide(@Nullable CompanionRecord record, long itemGen, @Nonnull UUID holder,
                                  @Nullable CompanionAdmission.Refusal refusal) {
        if (record == null) {
            return Decision.IGNORE;
        }
        if (record.location().kind() != LocationKind.ITEM || record.generation() != itemGen) {
            return Decision.STALE;
        }
        if (holder.equals(record.ownerUuid())) {
            return Decision.ALREADY_OWNED;
        }
        if (record.ownerUuid() == null) {
            return Decision.IGNORE;
        }
        return refusal == null ? Decision.TRANSFER : Decision.REFUSE;
    }

    /**
     * {@link #decide(CompanionRecord, long, UUID, CompanionAdmission.Refusal)} under the server's
     * mode: only {@code FOLLOWS_ITEM} moves a companion to the player holding its item, so the
     * other modes ignore an item owned by someone else.
     */
    @Nonnull
    public static Decision decide(@Nonnull CaptureItemOwnershipMode mode, @Nullable CompanionRecord record,
                                  long itemGen, @Nonnull UUID holder, @Nullable CompanionAdmission.Refusal refusal) {
        Decision decision = decide(record, itemGen, holder, refusal);
        boolean moves = decision == Decision.TRANSFER || decision == Decision.REFUSE;
        return moves && mode != CaptureItemOwnershipMode.FOLLOWS_ITEM ? Decision.IGNORE : decision;
    }

    /**
     * Whether {@code holder} may take the item into their inventory. A stale copy, the owner's own
     * item and an unowned capture always pass.
     *
     * @param blockIneligibleHolders the item config's {@code Capture.BlockIneligibleHolders}
     */
    @Nonnull
    public static Pickup pickup(@Nonnull CaptureItemOwnershipMode mode, @Nullable CompanionRecord record,
                                long itemGen, @Nonnull UUID holder, boolean blockIneligibleHolders) {
        if (decide(record, itemGen, holder, null) != Decision.TRANSFER) {
            return Pickup.ALLOW;
        }
        return switch (mode) {
            case OWNER_ONLY -> Pickup.REFUSE_NOT_OWNER;
            case CHANGES_ON_RELEASE -> Pickup.ALLOW;
            case FOLLOWS_ITEM -> blockIneligibleHolders ? Pickup.CHECK_LIMITS : Pickup.ALLOW;
        };
    }

    /**
     * The record owner after a capture, the same in every mode: a capture never clears the owner.
     * An unowned body gets the capturing player when it is already tamed or the item tames it;
     * a wild body caught by an item that does not tame stays unowned (null).
     */
    @Nullable
    public static UUID captureOwner(@Nullable UUID bodyOwner, boolean bodyTamed, boolean tamesTarget,
                                    @Nonnull UUID capturingPlayer) {
        if (bodyOwner != null) {
            return bodyOwner;
        }
        return bodyTamed || tamesTarget ? capturingPlayer : null;
    }

    /** What a release by {@code releaser} does with the owner of a record owned by {@code recordOwner}. */
    @Nonnull
    public static Release release(@Nonnull CaptureItemOwnershipMode mode, @Nullable UUID recordOwner,
                                  @Nonnull UUID releaser) {
        if (recordOwner == null) {
            return Release.UNOWNED;
        }
        if (recordOwner.equals(releaser)) {
            return Release.KEEP_OWNER;
        }
        return mode == CaptureItemOwnershipMode.OWNER_ONLY ? Release.REFUSE_NOT_OWNER : Release.ASSIGN_RELEASER;
    }

    /**
     * True when an item at {@code itemGen} no longer holds its companion: the record is gone, is
     * not in an item, or moved on to another generation (Recall, Forget, a later capture).
     */
    public static boolean isStale(@Nullable CompanionRecord record, long itemGen) {
        return record == null || record.location().kind() != LocationKind.ITEM || record.generation() != itemGen;
    }

    /** The record as the new holder would own it; generation unchanged, the item still holds it. */
    @Nonnull
    public static CompanionRecord asOwnedBy(@Nonnull CompanionRecord record, @Nonnull UUID holder, @Nullable String name) {
        return record.toBuilder().ownerUuid(holder).ownerName(name).build();
    }
}

package com.alechilles.alecstamework.companion.item;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Spec 8.14: whether a capture item entering a player's inventory moves its companion's owner.
 * Callers check the item config's {@code OwnershipFollowsHolder} (false while ClearsOwner) first.
 */
public final class CaptureItemOwnership {
    public enum Decision { IGNORE, STALE, ALREADY_OWNED, TRANSFER, REFUSE }

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

    /** The record as the new holder would own it; generation unchanged, the item still holds it. */
    @Nonnull
    public static CompanionRecord asOwnedBy(@Nonnull CompanionRecord record, @Nonnull UUID holder, @Nullable String name) {
        return record.toBuilder().ownerUuid(holder).ownerName(name).build();
    }
}

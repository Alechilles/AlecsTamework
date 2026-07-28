package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Canonical structural slot and its shared-operation reservation fence.
 *
 * <p>The residency revision survives releases, providing a monotonic aggregate revision for
 * rebuildable occupancy projections.</p>
 */
public record CoopSlot(
        @Nonnull CoopSlotKey key,
        long residencyRevision,
        @Nullable OperationId activeOperationId,
        @Nullable ProfileId reservedProfileId
) {
    public CoopSlot {
        if (key == null || residencyRevision < 0) {
            throw new IllegalArgumentException("Valid coop slot and revision are required");
        }
        if ((activeOperationId == null) != (reservedProfileId == null)) {
            throw new IllegalArgumentException(
                    "Coop reservation operation and profile must be present together"
            );
        }
    }

    /** Creates one unreserved slot before its first occupancy. */
    @Nonnull
    public static CoopSlot unoccupied(@Nonnull CoopSlotKey key) {
        return new CoopSlot(key, 0, null, null);
    }

    public boolean reserved() {
        return activeOperationId != null;
    }
}

package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Exposes immutable, rebuildable coop projections to world-facing gameplay.
 *
 * <p>This boundary keeps persistence composition and adapter access out of the tick system.
 * Snapshot reads never open storage or wait for durable work.</p>
 */
public final class DirectLiveCoopProjectionView {
    private final PersistenceDomainFacades facades;

    /** Creates the projection-only view over the shared replacement facade bundle. */
    public DirectLiveCoopProjectionView(
            @Nonnull PersistenceDomainFacades facades
    ) {
        if (facades == null) {
            throw new IllegalArgumentException(
                    "Persistence domain facades are required"
            );
        }
        this.facades = facades;
    }

    /** Returns the current immutable coop occupancy projection. */
    @Nonnull
    public Map<CoopSlotKey, CoopOccupancy> coopSnapshot() {
        return facades.queries().projectedCoopSnapshot();
    }

    /** Returns the current immutable companion profile projection. */
    @Nonnull
    public Map<ProfileId, CompanionProfileProjectionState> profileSnapshot() {
        return facades.queries().projectedProfileSnapshot();
    }
}

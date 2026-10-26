package com.alechilles.alecstamework.companion.index;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Where a record lives on server networks (spec 13.2). World-bound records stay on
 * the server whose world holds the companion; portable records follow the owner.
 */
public enum RecordScope {
    WORLD_BOUND, PORTABLE;

    /**
     * The scope a record has, decided by its location alone: STORED, and ITEM, DEAD and LOST with an
     * owner, follow the owner, who can restore, revive or recover them on any server. LIVE, COOP,
     * RELEASED and unowned ITEM, DEAD and LOST stay with the world.
     */
    @Nonnull
    public static RecordScope of(@Nonnull LocationKind kind, @Nullable UUID ownerUuid) {
        return switch (kind) {
            case STORED -> PORTABLE;
            case ITEM, DEAD, LOST -> ownerUuid != null ? PORTABLE : WORLD_BOUND;
            case LIVE, COOP, RELEASED -> WORLD_BOUND;
        };
    }
}

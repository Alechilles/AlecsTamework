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
     * The scope a record has, decided by its location alone: STORED, and ITEM with an owner, follow
     * the owner. LIVE, COOP, DEAD, LOST, RELEASED and unowned ITEM stay with the world. DEAD and LOST
     * stay world-bound until the network phase decides when they may move (spec 13.2).
     */
    @Nonnull
    public static RecordScope of(@Nonnull LocationKind kind, @Nullable UUID ownerUuid) {
        return switch (kind) {
            case STORED -> PORTABLE;
            case ITEM -> ownerUuid != null ? PORTABLE : WORLD_BOUND;
            case LIVE, COOP, DEAD, LOST, RELEASED -> WORLD_BOUND;
        };
    }
}

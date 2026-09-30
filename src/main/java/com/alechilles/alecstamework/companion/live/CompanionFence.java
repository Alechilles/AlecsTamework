package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** The generation fence for bodies (spec 6.8). Pure decision; the body system applies it. */
public final class CompanionFence {
    /** Stamp given to migrated 4.x bodies that are not the companion's current body (spec 12.4). */
    public static final long STALE_MIGRATED_GENERATION = -1L;

    private CompanionFence() {
    }

    @Nonnull
    public static FenceAction decide(@Nullable CompanionRecord record, boolean unreadable, long bodyGeneration,
                                     boolean bodyIsOwnedAndTamed, boolean anotherBodyLoaded) {
        if (bodyGeneration == STALE_MIGRATED_GENERATION) {
            return FenceAction.REMOVE;
        }
        if (unreadable) {
            return FenceAction.IGNORE;
        }
        if (record == null) {
            return bodyIsOwnedAndTamed ? FenceAction.ADOPT : FenceAction.REMOVE;
        }
        if (record.location().kind() == LocationKind.RELEASED) {
            return FenceAction.REMOVE;
        }
        if (anotherBodyLoaded) {
            return FenceAction.REMOVE;
        }
        if (bodyGeneration > record.generation()) {
            return FenceAction.ACCEPT_AND_RAISE;
        }
        if (record.location().kind() == LocationKind.LIVE && bodyGeneration == record.generation()) {
            return FenceAction.ACCEPT;
        }
        return FenceAction.REMOVE;
    }
}

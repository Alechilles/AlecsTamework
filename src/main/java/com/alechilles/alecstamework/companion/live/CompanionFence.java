package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** The generation fence for bodies (spec 6.8). Pure decision; the body system applies it. */
public final class CompanionFence {
    private CompanionFence() {
    }

    @Nonnull
    public static FenceAction decide(@Nullable CompanionRecord record, boolean unreadable, long bodyGeneration,
                                     boolean bodyIsOwnedAndTamed, boolean anotherBodyLoaded) {
        if (record == null) {
            // The unreadable mark only matters when there is no readable record to fence by.
            if (unreadable) {
                return FenceAction.IGNORE;
            }
            return bodyIsOwnedAndTamed && !anotherBodyLoaded ? FenceAction.ADOPT : FenceAction.REMOVE;
        }
        if (record.location().kind() == LocationKind.RELEASED) {
            return FenceAction.REMOVE;
        }
        // A newer body wins even over a registered body; the system displaces the older one.
        if (bodyGeneration > record.generation()) {
            return FenceAction.ACCEPT_AND_RAISE;
        }
        if (anotherBodyLoaded) {
            return FenceAction.REMOVE;
        }
        if (record.location().kind() == LocationKind.LIVE && bodyGeneration == record.generation()) {
            return FenceAction.ACCEPT;
        }
        return FenceAction.REMOVE;
    }
}

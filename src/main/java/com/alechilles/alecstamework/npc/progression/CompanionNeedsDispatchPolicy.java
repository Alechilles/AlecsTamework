package com.alechilles.alecstamework.npc.progression;

import javax.annotation.Nonnull;

/**
 * Decides whether a store-scoped needs batch should be queued.
 *
 * <p>The decision reads only the due-queue head and suppression membership. It does not resolve
 * entities or access a live world, so an idle store remains constant time regardless of member
 * count.</p>
 */
public final class CompanionNeedsDispatchPolicy {
    private CompanionNeedsDispatchPolicy() {
    }

    /** Returns the current dispatch decision for one store state. */
    @Nonnull
    public static Decision decide(@Nonnull CompanionNeedsRuntimeRegistry.WorldState state, long nowMs) {
        if (state.isDispatchPending()) {
            return Decision.PENDING;
        }
        if (!state.hasDue(nowMs) && !state.hasSuppressionActive()) {
            return Decision.IDLE;
        }
        return Decision.DISPATCH;
    }

    /** Result of the O(1) dispatch check. */
    public enum Decision {
        IDLE,
        PENDING,
        DISPATCH
    }
}

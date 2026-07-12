package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves damage-policy eligibility exclusively from a live target.
 *
 * <p>Persisted profile flags are intentionally not accepted here because they can be stale while
 * a companion is captured, in a coop, dead, lost, or otherwise dormant.</p>
 */
public final class TamedDamageTargetEligibilityResolver {
    /** Live eligibility states understood by the shared damage policy. */
    public enum Status {
        ELIGIBLE,
        INELIGIBLE,
        LIVE_TARGET_REQUIRED
    }

    @Nonnull
    public Status resolve(@Nullable Ref<EntityStore> targetRef,
                          @Nullable Store<EntityStore> store) {
        if (targetRef == null || store == null || !targetRef.isValid()) {
            return Status.LIVE_TARGET_REQUIRED;
        }
        try {
            return TamedStateResolver.isTamed(targetRef, store)
                    ? Status.ELIGIBLE
                    : Status.INELIGIBLE;
        } catch (Throwable ignored) {
            return Status.LIVE_TARGET_REQUIRED;
        }
    }
}

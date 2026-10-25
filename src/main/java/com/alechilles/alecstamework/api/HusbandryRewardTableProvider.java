package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Synchronous, read-only provider of the base reward roll for one husbandry action.
 *
 * <p>Tamework currently consults it for {@link HusbandryOutcomeKind#CULL_YIELD}
 * only. Providers must be side-effect-free: Tamework performs every roll and
 * drop. Return {@code null} to keep the family's configured drop list, or
 * {@link HusbandryRewardTable#empty()} to roll nothing.</p>
 */
@FunctionalInterface
public interface HusbandryRewardTableProvider {
    /** Resolves the base table for this action without changing Tamework or game state. */
    @Nullable
    HusbandryRewardTable resolve(@Nonnull HusbandryOutcomeContext context);
}

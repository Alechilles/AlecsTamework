package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Synchronous, read-only provider for one owner-scoped husbandry outcome.
 *
 * <p>Providers must be side-effect-free. Tamework alone performs chance rolls
 * and applies inventory, ECS, world, and breeding mutations.</p>
 */
@FunctionalInterface
public interface HusbandryOutcomeProvider {
    /** Resolves bounded modifiers without changing Tamework or game state. */
    @Nullable
    HusbandryOutcomeModifiers resolve(@Nonnull HusbandryOutcomeContext context);
}

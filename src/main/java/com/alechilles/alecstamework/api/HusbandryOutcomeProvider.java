package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Synchronous, read-only provider for one owner-scoped husbandry outcome. */
@FunctionalInterface
public interface HusbandryOutcomeProvider {
    /** Resolves bounded modifiers for the supplied action context. */
    @Nullable
    HusbandryOutcomeModifiers resolve(@Nonnull HusbandryOutcomeContext context);
}

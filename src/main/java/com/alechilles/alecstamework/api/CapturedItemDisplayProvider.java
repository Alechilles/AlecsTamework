package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Synchronous, read-only provider for presentation of one captured-NPC item. */
@FunctionalInterface
public interface CapturedItemDisplayProvider {
    /** Resolves an optional contribution without changing Tamework or game state. */
    @Nullable
    CapturedItemDisplayContribution resolve(@Nonnull CapturedItemDisplayContext context);
}

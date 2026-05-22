package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Describes one active trait that contributed to a custom trait effect value.
 */
public record TraitEffectContribution(
        @Nonnull String traitId,
        @Nullable String displayName,
        double value,
        @Nonnull String effectKey
) {
}

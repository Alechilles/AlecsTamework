package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/**
 * Handles a resolved trait effect value for a registered custom effect key.
 */
@FunctionalInterface
public interface TraitEffectHandler {
    boolean apply(@Nonnull TraitEffectContext context);
}

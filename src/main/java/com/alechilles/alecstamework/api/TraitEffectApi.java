package com.alechilles.alecstamework.api;

import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Public registry for custom trait effect keys consumed during Tamework trait resyncs.
 */
public interface TraitEffectApi {
    /**
     * Registers an idempotent handler for a trait effect key.
     *
     * @return a handle that unregisters this exact handler when closed.
     */
    @Nonnull
    AutoCloseable registerEffectKey(@Nonnull String effectKey, @Nonnull TraitEffectHandler handler);

    /**
     * Lists normalized effect keys that currently have registered handlers.
     */
    @Nonnull
    Set<String> listEffectKeys();
}

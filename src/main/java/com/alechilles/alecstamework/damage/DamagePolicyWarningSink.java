package com.alechilles.alecstamework.damage;

import javax.annotation.Nonnull;

/** Receives low-noise damage-policy warnings keyed by diagnostic category. */
@FunctionalInterface
interface DamagePolicyWarningSink {
    void warn(@Nonnull String category, @Nonnull String message);
}

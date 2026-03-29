package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface InteractionEffectHandler {
    boolean apply(@Nonnull InteractionEffectContext context, @Nonnull InteractionEffectSpec spec);
}

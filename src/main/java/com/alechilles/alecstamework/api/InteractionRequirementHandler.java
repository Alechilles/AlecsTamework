package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

@FunctionalInterface
public interface InteractionRequirementHandler {
    boolean test(@Nonnull InteractionRequirementContext context, @Nonnull InteractionRequirementSpec spec);
}

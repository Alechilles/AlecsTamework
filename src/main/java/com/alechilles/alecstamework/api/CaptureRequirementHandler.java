package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/** Extension callback for custom, side-effect-free capture eligibility. */
@FunctionalInterface
public interface CaptureRequirementHandler {
    @Nonnull
    CaptureRequirementDecision test(@Nonnull CaptureRequirementContext context,
                                    @Nonnull CaptureRequirementSpec spec);
}

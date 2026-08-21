package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Marks one care activity as qualified for the shared owner care-credit gate. */
public record CareCreditOutcomeView(
        @Nonnull UUID companionId,
        @Nullable UUID ownerId
) {
    public CareCreditOutcomeView {
        companionId = Objects.requireNonNull(companionId, "companionId");
    }
}

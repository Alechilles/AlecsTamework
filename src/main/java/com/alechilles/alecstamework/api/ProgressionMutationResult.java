package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ProgressionMutationResult(@Nonnull ProgressionMutationStatus status,
                                        @Nonnull String message,
                                        @Nullable ProgressionView progression) {
    public ProgressionMutationResult {
        status = Objects.requireNonNull(status);
        message = Objects.requireNonNullElse(message, "");
    }

    public boolean applied() {
        return status == ProgressionMutationStatus.APPLIED;
    }
}

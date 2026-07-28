package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable typed result envelope for bonded-companion queries and mutations. */
public record BondedCompanionResult<T>(
        @Nonnull BondedCompanionResultCode code,
        @Nullable T value,
        @Nullable String reason
) {
    public BondedCompanionResult {
        code = Objects.requireNonNull(code, "code");
        reason = reason == null || reason.isBlank() ? null : reason.trim();
        if (code != BondedCompanionResultCode.SUCCESS && reason == null) {
            throw new IllegalArgumentException(
                    "Non-success bonded-companion results require a reason."
            );
        }
    }

    public boolean successful() {
        return code == BondedCompanionResultCode.SUCCESS;
    }

    @Nonnull
    public static <T> BondedCompanionResult<T> unavailable(
            @Nonnull String reason
    ) {
        return new BondedCompanionResult<>(
                BondedCompanionResultCode.UNAVAILABLE,
                null,
                Objects.requireNonNull(reason, "reason")
        );
    }
}

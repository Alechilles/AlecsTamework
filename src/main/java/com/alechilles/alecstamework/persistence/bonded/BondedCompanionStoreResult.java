package com.alechilles.alecstamework.persistence.bonded;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable domain outcome for one bonded store mutation. */
public record BondedCompanionStoreResult<T>(
        @Nonnull Code code,
        @Nullable T value,
        @Nullable String reason,
        boolean replayed
) {
    /** Finite outcomes independent of any storage adapter. */
    public enum Code {
        APPLIED, NOT_FOUND, NOT_OWNER, REVISION_CONFLICT, INVALID_STATE,
        CONFLICT, IDEMPOTENCY_CONFLICT, VALIDATION_FAILED, STORAGE_FAILURE
    }

    public BondedCompanionStoreResult {
        code = Objects.requireNonNull(code, "code");
        reason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    /** Returns a replay view while preserving the stored terminal outcome. */
    public BondedCompanionStoreResult<T> asReplay() {
        return new BondedCompanionStoreResult<>(code, value, reason, true);
    }
}

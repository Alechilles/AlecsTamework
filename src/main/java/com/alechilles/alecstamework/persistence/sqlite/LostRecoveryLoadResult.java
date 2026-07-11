package com.alechilles.alecstamework.persistence.sqlite;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed profile-first read outcome for a potentially recoverable lost NPC. */
public record LostRecoveryLoadResult(@Nonnull Status status,
                                     @Nullable LostRecoveryEnvelope envelope,
                                     @Nullable Failure failure,
                                     @Nullable String detail,
                                     @Nullable Throwable cause) {
    public enum Status {
        FOUND,
        NOT_FOUND,
        LEGACY_UNVERIFIED,
        FAILED
    }

    public enum Failure {
        INVALID_INPUT,
        SQL_ERROR,
        INVALID_JSON,
        INVALID_FIELD,
        UNSUPPORTED_FORMAT_VERSION,
        SOURCE_MISSING,
        SOURCE_MISMATCH,
        SNAPSHOT_MISSING,
        SNAPSHOT_HASH_MISSING,
        SNAPSHOT_HASH_INVALID,
        SNAPSHOT_DECODE_FAILED,
        REPLACEMENT_PRESENT,
        DUPLICATE_ACTIVE_ROWS,
        PROFILE_NOT_FOUND,
        PROFILE_LOOKUP_CONFLICT,
        PROFILE_CURRENT_MISMATCH
    }

    @Nonnull
    static LostRecoveryLoadResult found(@Nonnull LostRecoveryEnvelope envelope) {
        return new LostRecoveryLoadResult(Status.FOUND, envelope, null, null, null);
    }

    @Nonnull
    static LostRecoveryLoadResult notFound() {
        return new LostRecoveryLoadResult(Status.NOT_FOUND, null, null, null, null);
    }

    @Nonnull
    static LostRecoveryLoadResult notAwaiting(@Nonnull LostRecoveryEnvelope envelope) {
        return new LostRecoveryLoadResult(
                Status.NOT_FOUND, envelope, Failure.REPLACEMENT_PRESENT, "replacement_present", null);
    }

    @Nonnull
    static LostRecoveryLoadResult legacy(@Nonnull LostRecoveryEnvelope envelope,
                                         @Nonnull Failure failure) {
        return new LostRecoveryLoadResult(
                Status.LEGACY_UNVERIFIED, envelope, failure, failure.name().toLowerCase(), null);
    }

    @Nonnull
    static LostRecoveryLoadResult failed(@Nonnull Failure failure,
                                         @Nullable LostRecoveryEnvelope envelope,
                                         @Nullable String detail,
                                         @Nullable Throwable cause) {
        return new LostRecoveryLoadResult(Status.FAILED, envelope, failure, detail, cause);
    }
}

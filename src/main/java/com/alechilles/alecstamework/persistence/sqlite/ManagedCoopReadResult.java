package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.SQLException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Typed boundary for managed-coop reads that must never collapse corruption into empty state.
 *
 * @param <T> immutable value returned by a successful read
 */
public record ManagedCoopReadResult<T>(@Nonnull Status status,
                                       @Nullable T value,
                                       @Nullable Failure failure) {
    public ManagedCoopReadResult {
        if (status == null) {
            throw new IllegalArgumentException("Managed coop read status is required");
        }
        if (status == Status.LOADED && value == null) {
            throw new IllegalArgumentException("A loaded managed coop read requires a value");
        }
        if (status != Status.LOADED && value != null) {
            throw new IllegalArgumentException("Only loaded managed coop reads may carry a value");
        }
        if (status == Status.FAILED && failure == null) {
            throw new IllegalArgumentException("A failed managed coop read requires failure details");
        }
        if (status != Status.FAILED && failure != null) {
            throw new IllegalArgumentException("Successful managed coop reads cannot carry a failure");
        }
    }

    public enum Status {
        LOADED,
        NOT_FOUND,
        FAILED
    }

    public enum FailureKind {
        INVALID_INPUT,
        SQL_ERROR,
        INTEGRITY_VIOLATION
    }

    public record Failure(@Nonnull FailureKind kind,
                          @Nonnull String detail,
                          @Nullable Throwable cause) {
        public Failure {
            if (kind == null) {
                throw new IllegalArgumentException("Managed coop read failure kind is required");
            }
            if (detail == null || detail.isBlank()) {
                detail = "managed_coop_read_failed";
            }
        }
    }

    @Nonnull
    public static <T> ManagedCoopReadResult<T> loaded(@Nonnull T value) {
        return new ManagedCoopReadResult<>(Status.LOADED, value, null);
    }

    @Nonnull
    public static <T> ManagedCoopReadResult<T> notFound() {
        return new ManagedCoopReadResult<>(Status.NOT_FOUND, null, null);
    }

    @Nonnull
    public static <T> ManagedCoopReadResult<T> invalidInput(@Nonnull String detail) {
        return failed(FailureKind.INVALID_INPUT, detail, null);
    }

    @Nonnull
    public static <T> ManagedCoopReadResult<T> sqlFailure(@Nonnull SQLException exception) {
        return failed(FailureKind.SQL_ERROR, exception.getMessage(), exception);
    }

    @Nonnull
    public static <T> ManagedCoopReadResult<T> integrityFailure(@Nonnull Throwable exception) {
        return failed(FailureKind.INTEGRITY_VIOLATION, exception.getMessage(), exception);
    }

    @Nonnull
    private static <T> ManagedCoopReadResult<T> failed(@Nonnull FailureKind kind,
                                                       @Nullable String detail,
                                                       @Nullable Throwable cause) {
        return new ManagedCoopReadResult<>(
                Status.FAILED,
                null,
                new Failure(kind, detail == null ? "managed_coop_read_failed" : detail, cause)
        );
    }
}

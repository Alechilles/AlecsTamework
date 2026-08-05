package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.sql.SQLException;
import java.util.Locale;
import javax.annotation.Nonnull;

/** Converts SQLite/JDBC failures into the replacement kernel's stable failure vocabulary. */
public final class SqliteFailureClassifier {
    private SqliteFailureClassifier() {
    }

    /** Classifies a failure without requiring callers to inspect exception text. */
    @Nonnull
    public static StorageFailure classify(@Nonnull Throwable failure, @Nonnull String operation) {
        if (failure == null) {
            throw new IllegalArgumentException("SQLite failure is required");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("SQLite operation is required");
        }

        SQLException sqlFailure = findSqlFailure(failure);
        String message = allMessages(failure);
        int errorCode = sqlFailure == null ? 0 : sqlFailure.getErrorCode();

        if (errorCode == 5 || errorCode == 6 || containsAny(message, "SQLITE_BUSY", "SQLITE_LOCKED")) {
            return result(StorageFailureKind.BUSY, "sqlite_busy", operation, true, failure);
        }
        if (containsAny(message, "TIMEOUT", "TIMED OUT", "INTERRUPTED")) {
            return result(StorageFailureKind.TIMEOUT, "sqlite_timeout", operation, true, failure);
        }
        if (errorCode == 11 || errorCode == 26
                || containsAny(message, "SQLITE_CORRUPT", "SQLITE_NOTADB", "NOT A DATABASE", "MALFORMED")) {
            return result(StorageFailureKind.CORRUPT, "sqlite_corrupt", operation, false, failure);
        }
        if (errorCode == 1 || containsAny(message, "NO SUCH TABLE", "NO SUCH COLUMN", "SCHEMA")) {
            return result(StorageFailureKind.SCHEMA, "sqlite_schema", operation, false, failure);
        }
        if (errorCode == 10 || errorCode == 13 || errorCode == 14
                || containsAny(message, "SQLITE_IOERR", "SQLITE_FULL", "SQLITE_CANTOPEN", "READONLY")) {
            return result(StorageFailureKind.IO, "sqlite_io", operation, false, failure);
        }
        if (containsAny(message, "DRIVER_MISSING", "NATIVE_UNAVAILABLE", "DATABASE_MISSING")) {
            return result(StorageFailureKind.UNAVAILABLE, "sqlite_unavailable", operation, false, failure);
        }
        if (containsAny(message, "CAPTURE_PREPARE_NOT_EXACT_LIVE_PROFILE")) {
            return result(
                    StorageFailureKind.UNKNOWN,
                    "sqlite_operation_precondition",
                    operation,
                    true,
                    failure
            );
        }
        return result(StorageFailureKind.UNKNOWN, "sqlite_unknown", operation, false, failure);
    }

    private static StorageFailure result(StorageFailureKind kind,
                                         String code,
                                         String operation,
                                         boolean retryable,
                                         Throwable cause) {
        return new StorageFailure(kind, code, operation.trim(), retryable, cause);
    }

    private static SQLException findSqlFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(' ').append(current.getMessage().toUpperCase(Locale.ROOT));
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

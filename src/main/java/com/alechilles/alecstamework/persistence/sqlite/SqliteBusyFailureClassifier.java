package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Locale;
import javax.annotation.Nonnull;

/** Classifies retryable SQLite lock failures across wrapped exception chains. */
final class SqliteBusyFailureClassifier {
    private SqliteBusyFailureClassifier() {
    }

    static boolean isTransient(@Nonnull Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && isBusyMessage(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isBusyMessage(@Nonnull String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("sqlite_busy")
                || normalized.contains("sqlite_locked")
                || normalized.contains("database is locked")
                || normalized.contains("database is busy")
                || normalized.contains("database table is locked");
    }
}

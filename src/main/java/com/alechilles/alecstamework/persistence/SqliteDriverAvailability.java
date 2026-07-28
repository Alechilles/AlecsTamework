package com.alechilles.alecstamework.persistence;

import java.sql.SQLException;
import javax.annotation.Nullable;

/** Classifies the narrow driver-loading failures that prevent SQLite startup. */
public final class SqliteDriverAvailability {
    private SqliteDriverAvailability() {
    }

    /** Returns whether a cause chain proves the SQLite driver is unavailable. */
    public static boolean isUnavailable(@Nullable Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof LinkageError) {
                return true;
            }
            if (current instanceof SQLException sqlFailure
                    && ("sqlite_native_unavailable".equals(sqlFailure.getMessage())
                    || "sqlite_jdbc_driver_missing".equals(sqlFailure.getMessage()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

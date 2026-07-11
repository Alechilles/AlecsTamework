package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.SQLException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** SQL exception reserved for malformed or internally contradictory managed-coop rows. */
final class ManagedCoopIntegrityException extends SQLException {
    ManagedCoopIntegrityException(@Nonnull String message) {
        super(message);
    }

    ManagedCoopIntegrityException(@Nonnull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}

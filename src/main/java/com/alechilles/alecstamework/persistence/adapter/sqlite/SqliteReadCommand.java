package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import javax.annotation.Nonnull;

/**
 * Complete replacement SQLite read command.
 *
 * @param kind stable read identifier
 * @param priority isolated executor lane
 * @param work typed query
 * @param <T> immutable read value
 */
public record SqliteReadCommand<T>(@Nonnull PersistenceReadKind kind,
                                   @Nonnull PersistenceReadPriority priority,
                                   @Nonnull SqliteReadWork<T> work) {
    public SqliteReadCommand {
        if (kind == null || priority == null || work == null) {
            throw new IllegalArgumentException("Complete SQLite read command is required");
        }
    }
}

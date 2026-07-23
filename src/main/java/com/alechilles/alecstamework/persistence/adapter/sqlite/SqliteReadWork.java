package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.sql.Connection;
import javax.annotation.Nonnull;

/** Typed SQLite read that must explicitly return found, absent, or failed. */
@FunctionalInterface
public interface SqliteReadWork<T> {
    @Nonnull
    PersistenceReadResult<T> execute(@Nonnull Connection connection) throws Exception;
}

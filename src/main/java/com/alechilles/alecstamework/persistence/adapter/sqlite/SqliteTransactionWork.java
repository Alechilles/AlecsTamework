package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import javax.annotation.Nonnull;

/** SQLite adapter work executed inside one explicit replacement transaction. */
@FunctionalInterface
public interface SqliteTransactionWork<T> {
    T execute(@Nonnull Connection connection) throws Exception;
}

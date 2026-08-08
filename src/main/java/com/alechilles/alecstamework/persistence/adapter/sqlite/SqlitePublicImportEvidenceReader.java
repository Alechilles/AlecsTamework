package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads the durable proof that this target originated from a supported public import. */
final class SqlitePublicImportEvidenceReader {
    private final Connection connection;

    SqlitePublicImportEvidenceReader(Connection connection) {
        this.connection = connection;
    }

    boolean hasSupportedImport() {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM import_manifest
                WHERE source_schema_version BETWEEN 2 AND 4
                  AND importer_version = 1
                LIMIT 1
                """);
             ResultSet row = statement.executeQuery()) {
            return row.next();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "public_import_manifest_read_failed",
                    failure
            );
        }
    }
}

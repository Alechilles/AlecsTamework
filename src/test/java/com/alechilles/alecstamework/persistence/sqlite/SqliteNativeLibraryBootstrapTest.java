package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteNativeLibraryBootstrapTest {
    @TempDir
    Path tempDir;

    @Test
    void configuresExtractedNativeLibraryForDriverLoading() throws Exception {
        SqliteConnectionManager connectionManager = new SqliteConnectionManager(tempDir.resolve("bootstrap-test.sqlite"));

        Path extractedLibrary = SqliteNativeLibraryBootstrap.currentExtractionPath();
        assertTrue(Files.exists(extractedLibrary));
        assertEquals(extractedLibrary.getParent().toString(), System.getProperty(SqliteNativeLibraryBootstrap.SQLITE_LIB_PATH_PROPERTY));
        assertEquals(extractedLibrary.getFileName().toString(), System.getProperty(SqliteNativeLibraryBootstrap.SQLITE_LIB_NAME_PROPERTY));

        try (var connection = connectionManager.openConnection()) {
            assertTrue(connection.isValid(1));
        }
    }
}

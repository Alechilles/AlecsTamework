package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteMigrationBackupBoundaryArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/alechilles/alecstamework/persistence/sqlite/SqliteMigrationBackupService.java");

    @Test
    void migrationProtectionRemainsLimitedToTameworkSqlite() throws Exception {
        String source = Files.readString(SERVICE);

        assertTrue(source.contains("VACUUM INTO"));
        assertTrue(source.contains("tamework_sqlite_only"));
        assertTrue(source.contains("hytale_server_operator"));
        assertFalse(source.contains("Files.copy("));
        assertFalse(source.contains("Files.walk("));
        assertFalse(source.contains("backupUniverse"));
        assertFalse(source.contains("backupWorld"));
        assertFalse(source.contains("getUniverse()"));
    }
}

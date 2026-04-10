package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces SQLite startup resilience guardrails.
 */
class SqliteStartupResilienceGuardTest {
    private static final Path SQLITE_CONNECTION_MANAGER_PATH = Paths.get(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "persistence",
            "sqlite",
            "SqliteConnectionManager.java"
    );

    @Test
    void openConnectionWrapsNativeLinkageErrorsAsSqlExceptions() throws IOException {
        String content = Files.readString(SQLITE_CONNECTION_MANAGER_PATH, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("catch (LinkageError error)"),
                "SqliteConnectionManager.openConnection must guard LinkageError from native sqlite loading."
        );
        assertTrue(
                content.contains("throw new SQLException(\"sqlite_native_unavailable\", error);"),
                "SqliteConnectionManager.openConnection must wrap sqlite native linkage errors as SQLException."
        );
    }
}

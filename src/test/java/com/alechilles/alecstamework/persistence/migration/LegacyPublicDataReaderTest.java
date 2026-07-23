package com.alechilles.alecstamework.persistence.migration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact row-model tests for public source reading before target mutation begins. */
class LegacyPublicDataReaderTest {
    private static final String RESOURCE_ROOT = "/persistence-consolidation/";

    @TempDir
    Path tempDir;

    @Test
    void readsRepresentativeV4RowsWithoutNormalizingSignedValuesOrUnicode() throws Exception {
        Path source = tempDir.resolve("public-v4.sqlite");
        materialize("public-v4-representative.sql", source);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            LegacyPublicData data = new LegacyPublicDataReader().read(connection, 4);

            assertEquals(6, data.profiles().size());
            assertEquals(7, data.aliases().size());
            assertEquals(2, data.toolLinks().size());
            assertEquals(3, data.snapshots().size());
            assertEquals(1, data.coopSlots().size());
            assertEquals(6, data.profileStates().size());
            assertEquals(1, data.extensionData().size());
            assertEquals("Active Ω", data.profiles().getFirst().displayName());
            assertEquals(-62135596800000L, data.profiles().getFirst().createdAtMs());
            assertEquals("world-a|fixture-coop|10|64|20|0",
                    data.coopSlots().getFirst().coopKey());
            assertTrue(data.extensionData().getFirst().jsonPayload().contains("-3000"));
        }
    }

    @Test
    void versionControlsOnlyColumnsAndTablesThatActuallyExist() throws Exception {
        Path v2 = tempDir.resolve("public-v2.sqlite");
        materialize("public-v2-empty.sql", v2);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + v2)) {
            LegacyPublicData data = new LegacyPublicDataReader().read(connection, 2);
            assertTrue(data.profiles().isEmpty());
            assertTrue(data.extensionData().isEmpty());
        }
    }

    private void materialize(String resource, Path database) throws Exception {
        String sql = expand(resource, new HashSet<>());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            for (String command : splitStatements(sql)) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private String expand(String resource, Set<String> stack) throws Exception {
        assertTrue(stack.add(resource));
        StringBuilder expanded = new StringBuilder();
        for (String line : resource(resource).replace("\r\n", "\n")
                .replace('\r', '\n').split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-- @include ")) {
                expanded.append(expand(trimmed.substring("-- @include ".length()).trim(), stack));
            } else {
                expanded.append(line).append('\n');
            }
        }
        stack.remove(resource);
        return expanded.toString();
    }

    private List<String> splitStatements(String sql) {
        ArrayList<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char value = sql.charAt(index);
            current.append(value);
            if (value == '\'' && quoted && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '\'') {
                current.append(sql.charAt(++index));
            } else if (value == '\'') {
                quoted = !quoted;
            } else if (value == ';' && !quoted) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private String resource(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE_ROOT + name)) {
            if (input == null) {
                throw new IllegalArgumentException(name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

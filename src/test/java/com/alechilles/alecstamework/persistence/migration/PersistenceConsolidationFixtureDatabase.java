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

/** Materializes the immutable consolidation SQL fixtures for adapter and importer tests. */
final class PersistenceConsolidationFixtureDatabase {
    private static final String RESOURCE_ROOT = "/persistence-consolidation/";

    private PersistenceConsolidationFixtureDatabase() {
    }

    static void materialize(String resource, Path database) throws Exception {
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

    private static String expand(String resource, Set<String> stack) throws Exception {
        if (!stack.add(resource)) {
            throw new IllegalArgumentException("Fixture include cycle: " + resource);
        }
        StringBuilder expanded = new StringBuilder();
        for (String line : read(resource).replace("\r\n", "\n")
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

    private static List<String> splitStatements(String sql) {
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
        if (quoted) {
            throw new IllegalArgumentException("Unterminated fixture SQL string");
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return List.copyOf(statements);
    }

    private static String read(String resource) throws Exception {
        try (InputStream input = PersistenceConsolidationFixtureDatabase.class
                .getResourceAsStream(RESOURCE_ROOT + resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing fixture: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared SQLite catalog inspection used by one schema authority. */
final class SqliteSchemaDefinitionCatalog {
    private static final Pattern DEFINITION = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:(UNIQUE)\\s+)?"
                    + "(TABLE|INDEX)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\b"
    );

    private SqliteSchemaDefinitionCatalog() {
    }

    static Set<SchemaObject> requiredObjects(
            Set<String> tables, Set<String> indexes
    ) {
        HashSet<SchemaObject> objects = new HashSet<>();
        tables.forEach(table -> objects.add(new SchemaObject("table", table)));
        indexes.forEach(index -> objects.add(new SchemaObject("index", index)));
        return Set.copyOf(objects);
    }

    static Map<SchemaObject, String> requiredDefinitions(
            String script, Set<SchemaObject> required
    ) {
        Map<SchemaObject, String> definitions = new HashMap<>();
        for (String statement : SqlScriptParser.statements(script)) {
            Matcher matcher = DEFINITION.matcher(statement);
            if (!matcher.find()) {
                continue;
            }
            SchemaObject object = new SchemaObject(
                    matcher.group(2).toLowerCase(Locale.ROOT),
                    matcher.group(3)
            );
            if (!required.contains(object)) {
                continue;
            }
            if (definitions.put(object, normalize(statement)) != null) {
                throw new IllegalStateException(
                        "Duplicate replacement schema definition: "
                                + object.name()
                );
            }
        }
        if (!definitions.keySet().equals(required)) {
            throw new IllegalStateException(
                    "Replacement schema definitions do not match required objects"
            );
        }
        return Map.copyOf(definitions);
    }

    static Inspection inspect(Connection connection) throws SQLException {
        return inspect(connection, false);
    }

    /** Preserves the shipped v2 gateway's historical DDL comparison rules. */
    static Inspection inspectReadOnlyCompatibility(Connection connection)
            throws SQLException {
        return inspect(connection, true);
    }

    private static Inspection inspect(
            Connection connection, boolean readOnlyCompatibility
    ) throws SQLException {
        Set<SchemaObject> objects = new HashSet<>();
        Map<SchemaObject, String> definitions = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT type, name, sql FROM sqlite_master
                     WHERE name NOT LIKE 'sqlite_%'
                     """)) {
            while (rows.next()) {
                SchemaObject object = new SchemaObject(
                        rows.getString("type"), rows.getString("name")
                );
                objects.add(object);
                String sql = rows.getString("sql");
                if (readOnlyCompatibility && sql == null) {
                    throw new SQLException("replacement_schema_sql_missing");
                }
                if (sql != null && (readOnlyCompatibility
                        || "table".equals(object.type())
                        || "index".equals(object.type()))) {
                    definitions.put(
                            object,
                            readOnlyCompatibility
                                    ? normalizeReadOnlyDdl(sql)
                                    : normalize(sql)
                    );
                }
            }
        }
        return new Inspection(Set.copyOf(objects), Map.copyOf(definitions));
    }

    static Inspection expectedReadOnlySchema(String script)
            throws SQLException {
        try (Connection authority = DriverManager.getConnection(
                "jdbc:sqlite::memory:"
        )) {
            for (String statement : SqlScriptParser.statements(script)) {
                if (!statement.isBlank()) {
                    try (Statement executor = authority.createStatement()) {
                        executor.execute(statement);
                    }
                }
            }
            return inspectReadOnlyCompatibility(authority);
        }
    }

    static String normalize(String sql) {
        StringBuilder normalized = new StringBuilder(sql.length());
        char closingQuote = 0;
        boolean pendingWhitespace = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (closingQuote != 0) {
                normalized.append(character);
                if (character == closingQuote) {
                    if (closingQuote != ']' && index + 1 < sql.length()
                            && sql.charAt(index + 1) == closingQuote) {
                        normalized.append(sql.charAt(++index));
                    } else {
                        closingQuote = 0;
                    }
                }
                continue;
            }
            if (Character.isWhitespace(character)) {
                pendingWhitespace = normalized.length() > 0;
                continue;
            }
            if (pendingWhitespace) {
                normalized.append(' ');
                pendingWhitespace = false;
            }
            normalized.append(Character.toLowerCase(character));
            closingQuote = switch (character) {
                case '\'', '"', 96 -> character;
                case '[' -> ']';
                default -> 0;
            };
        }
        return normalized.toString();
    }

    private static String normalizeReadOnlyDdl(String sql) {
        String normalized = sql.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return normalized.endsWith(";")
                ? normalized.substring(0, normalized.length() - 1).trim()
                : normalized;
    }

    record SchemaObject(String type, String name) {
    }

    record Inspection(Set<SchemaObject> objects,
                      Map<SchemaObject, String> definitions) {
    }
}

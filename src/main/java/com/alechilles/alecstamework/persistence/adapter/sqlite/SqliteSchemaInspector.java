package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and normalizes the complete user-defined SQLite schema. */
final class SqliteSchemaInspector {
    private static final Pattern SCHEMA_DEFINITION = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(?:(UNIQUE)\\s+)?(TABLE|INDEX)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\b"
    );

    private SqliteSchemaInspector() {
    }

    static SchemaInspection inspect(Connection connection) throws SQLException {
        if (connection == null) {
            throw new IllegalArgumentException("Schema connection is required");
        }
        HashSet<SchemaObject> objects = new HashSet<>();
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
                if ("table".equals(object.type())
                        || "index".equals(object.type())) {
                    String sql = rows.getString("sql");
                    if (sql != null) {
                        definitions.put(object, normalizeDefinition(sql));
                    }
                }
            }
        }
        return new SchemaInspection(Set.copyOf(objects), Map.copyOf(definitions));
    }

    static Map<SchemaObject, String> definitionsForScript(
            String script,
            Set<SchemaObject> requiredObjects
    ) {
        if (script == null || requiredObjects == null) {
            throw new IllegalArgumentException(
                    "Schema script and required objects are required"
            );
        }
        Map<SchemaObject, String> definitions = new HashMap<>();
        for (String statement : SqlScriptParser.statements(script)) {
            Matcher matcher = SCHEMA_DEFINITION.matcher(statement);
            if (!matcher.find()) {
                continue;
            }
            SchemaObject object = new SchemaObject(
                    matcher.group(2).toLowerCase(java.util.Locale.ROOT),
                    matcher.group(3)
            );
            if (!requiredObjects.contains(object)) {
                continue;
            }
            String previous = definitions.put(object, normalizeDefinition(statement));
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate replacement schema definition: " + object.name()
                );
            }
        }
        if (!definitions.keySet().equals(requiredObjects)) {
            throw new IllegalStateException(
                    "Replacement schema definitions do not match required objects"
            );
        }
        return Map.copyOf(definitions);
    }

    static byte[] resourceBytes(Class<?> anchor, String resource)
            throws Exception {
        try (var stream = anchor.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing replacement schema resource: " + resource
                );
            }
            return stream.readAllBytes();
        }
    }

    static String normalizedResourceText(Class<?> anchor, String resource)
            throws Exception {
        return new String(resourceBytes(anchor, resource), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    static String normalizeDefinition(String sql) {
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
                case '\'', '"', '`' -> character;
                case '[' -> ']';
                default -> 0;
            };
        }
        return normalized.toString();
    }

    record SchemaObject(String type, String name) {
    }

    record SchemaInspection(Set<SchemaObject> objects,
                            Map<SchemaObject, String> definitions) {
    }
}

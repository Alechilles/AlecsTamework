package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the immutable public-release and rejected-development SQL fixtures.
 *
 * <p>The fixtures are reviewable SQL instead of opaque database binaries. Tests materialize them
 * into SQLite so future import tests use the exact schema/data contract captured here.</p>
 */
class LegacyPersistenceFixtureTest {
    private static final String RESOURCE_ROOT = "/persistence-consolidation/";

    @TempDir
    Path tempDir;

    @Test
    void fixtureSourcesMatchTheirNormalizedHashes() throws Exception {
        JsonObject manifest = loadManifest();
        for (JsonElement element : manifest.getAsJsonArray("sourceFiles")) {
            JsonObject sourceFile = element.getAsJsonObject();
            String resource = sourceFile.get("resource").getAsString();
            String expected = sourceFile.get("normalizedLfSha256").getAsString();
            assertEquals(expected, sha256(normalizeLf(readResource(resource))), resource);
        }
    }

    @Test
    void everyFixtureMaterializesWithTheExpectedShape() throws Exception {
        JsonObject manifest = loadManifest();
        for (JsonElement element : manifest.getAsJsonArray("fixtures")) {
            JsonObject fixture = element.getAsJsonObject();
            String id = fixture.get("id").getAsString();
            Path database = tempDir.resolve(id + ".sqlite");
            materialize(fixture.get("resource").getAsString(), database);

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
                assertEquals("ok", queryString(connection, "PRAGMA integrity_check"), id);
                assertEquals(
                        fixture.get("maximumSchemaVersion").getAsInt(),
                        queryInt(connection, "SELECT MAX(version) FROM schema_migrations"),
                        id
                );
                assertEquals(
                        fixture.get("expectedTableCount").getAsInt(),
                        queryInt(
                                connection,
                                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'"
                                        + " AND name NOT LIKE 'sqlite_%'"
                        ),
                        id
                );
                assertEquals(
                        fixture.get("expectedForeignKeyViolations").getAsInt(),
                        countRows(connection, "PRAGMA foreign_key_check"),
                        id
                );
                for (var rowCount : fixture.getAsJsonObject("rowCounts").entrySet()) {
                    assertEquals(
                            rowCount.getValue().getAsInt(),
                            queryInt(connection, "SELECT COUNT(*) FROM " + rowCount.getKey()),
                            id + ": " + rowCount.getKey()
                    );
                }
            }
        }
    }

    @Test
    void representativeV4FixturePreservesReleaseEdgeCases() throws Exception {
        Path database = tempDir.resolve("representative-v4.sqlite");
        materialize("public-v4-representative.sql", database);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertTrue(hasColumn(connection, "coop_slots", "state_snapshot_json"));
            assertEquals(
                    "Active Ω",
                    queryString(
                            connection,
                            "SELECT display_name FROM npc_profiles"
                                    + " WHERE profile_id = '20000000-0000-0000-0000-000000000001'"
                    )
            );
            assertEquals(
                    -62135596800000L,
                    queryLong(
                            connection,
                            "SELECT created_at_ms FROM npc_profiles"
                                    + " WHERE profile_id = '20000000-0000-0000-0000-000000000001'"
                    )
            );
            assertEquals(
                    1,
                    queryInt(
                            connection,
                            "SELECT COUNT(*) FROM npc_uuid_aliases WHERE is_current = 0"
                    )
            );
            assertEquals(
                    4,
                    queryInt(
                            connection,
                            "SELECT COUNT(*) FROM profile_states"
                                    + " WHERE capture_active + death_active + lost_active + in_coop = 1"
                    )
            );
            assertEquals(
                    -3000L,
                    queryLong(
                            connection,
                            "SELECT json_extract(json_payload, '$.worldTimeMs')"
                                    + " FROM api_profile_data WHERE data_key = 'unicode'"
                    )
            );
        }
    }

    @Test
    void developmentFixturesRemainExplicitlyUnsupported() throws Exception {
        JsonObject manifest = loadManifest();
        int developmentFixtureCount = 0;
        for (JsonElement element : manifest.getAsJsonArray("fixtures")) {
            JsonObject fixture = element.getAsJsonObject();
            int version = fixture.get("maximumSchemaVersion").getAsInt();
            if (version <= 4) {
                continue;
            }
            developmentFixtureCount++;
            assertEquals(
                    "UNSUPPORTED_DEVELOPMENT_SCHEMA",
                    fixture.get("classification").getAsString()
            );
        }
        assertEquals(2, developmentFixtureCount);
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
        assertTrue(stack.add(resource), "fixture include cycle: " + resource);
        StringBuilder expanded = new StringBuilder();
        for (String line : normalizeLf(readResource(resource)).split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-- @include ")) {
                String include = trimmed.substring("-- @include ".length()).trim();
                expanded.append(expand(include, stack));
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
            char currentChar = sql.charAt(index);
            if (currentChar == '\'') {
                current.append(currentChar);
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    current.append(sql.charAt(++index));
                    continue;
                }
                quoted = !quoted;
                continue;
            }
            if (currentChar == ';' && !quoted) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(currentChar);
            }
        }
        assertFalse(quoted, "unterminated SQL string");
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countRows(Connection connection, String sql) throws Exception {
        int count = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                count++;
            }
        }
        return count;
    }

    private int queryInt(Connection connection, String sql) throws Exception {
        return (int) queryLong(connection, sql);
    }

    private long queryLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next(), sql);
            return row.getLong(1);
        }
    }

    private String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next(), sql);
            return row.getString(1);
        }
    }

    private JsonObject loadManifest() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                RESOURCE_ROOT + "fixture-manifest.json"
        )) {
            assertNotNull(stream, "persistence consolidation fixture manifest");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private String readResource(String resource) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(RESOURCE_ROOT + resource)) {
            assertNotNull(stream, resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String normalizeLf(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}

package com.alechilles.alecstamework.companion.migrate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Builds small 3.x/4.x databases for import tests from the SQL in {@code import-fixtures}, with
 * the fixture's short profile names replaced by real UUIDs so the rows map to records. Public so
 * the module test can use it.
 */
public final class ImportFixtures {
    public static final UUID LIVE = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    public static final UUID LIVE_NPC = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    public static final UUID BONDED_DEAD = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002");
    private static final Map<String, String> IDS = Map.of(
            "p-live", LIVE.toString(),
            "p-dead", "dddddddd-0000-0000-0000-000000000002",
            "p-coop", "dddddddd-0000-0000-0000-000000000003",
            "p-prov", "dddddddd-0000-0000-0000-000000000004",
            "b-active", "eeeeeeee-0000-0000-0000-000000000001",
            "b-dead", BONDED_DEAD.toString());

    private ImportFixtures() {
    }

    /** Writes a version 2 {@code tamework-state.sqlite} with four profiles into {@code dataDir}. */
    public static Path state(Path dataDir) throws Exception {
        Path file = Files.createDirectories(dataDir).resolve(LegacySource.STATE_FILE);
        execute(file, read("state-v2.sql"), read("state-data.sql"),
                "INSERT INTO schema_history (version, lineage, applied_at_ms, schema_hash) VALUES (2, "
                        + "'tamework-state', 1, '" + "0".repeat(64) + "')");
        return file;
    }

    /** Writes a {@code bonded-companions.sqlite} with two profiles into {@code dataDir}. */
    public static Path bonded(Path dataDir) throws Exception {
        Path file = Files.createDirectories(dataDir).resolve(LegacySource.BONDED_FILE);
        execute(file, read("bonded-v1.sql"), read("bonded-data.sql"));
        return file;
    }

    /** Every file under {@code folder} by relative path, with its bytes; empty when the folder is missing. */
    public static Map<String, String> contents(Path folder) throws IOException {
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(folder)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(file).toString().replace('\\', '/'),
                        Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
        }
        return files;
    }

    private static void execute(Path file, String... scripts) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            for (String script : scripts) {
                for (String sql : script.split(";\\s*(\\R|$)")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }

    private static String read(String fixture) throws IOException {
        try (InputStream stream = ImportFixtures.class.getResourceAsStream("/import-fixtures/" + fixture)) {
            if (stream == null) {
                throw new IOException("missing fixture " + fixture);
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + b + "\n");
            for (Map.Entry<String, String> id : IDS.entrySet()) {
                text = text.replace(id.getKey(), id.getValue());
            }
            return text;
        }
    }
}

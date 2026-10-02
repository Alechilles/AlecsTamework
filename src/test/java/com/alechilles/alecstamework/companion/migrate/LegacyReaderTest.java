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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyReaderTest {
    private static final String HASH = "0".repeat(64);
    private static final String ROUTED_HASH = "b72b00e5e77277f936866aa2f20555c3d35473379e40b6608890b9f0a382d5d7";
    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private static final String OLD_NPC = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String NPC = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String LEASED_NPC = "aaaaaaaa-0000-0000-0000-000000000003";
    private static final String COOP_KEY = "v1:d29ybGQtYQ:Y29vcA:10:64:-20:0";
    private static final String CHECKPOINT_JSON =
            "{\"profileId\":\"p-live\",\"worldKey\":\"world-a\",\"x\":1.0,\"y\":2.0,\"z\":3.0,\"capturedAtMs\":1480}";
    private static final String DEATH_JSON = "{\"fullState\":{\"roleId\":\"Tamed_Wolf\"},\"diedAtMs\":-900,"
            + "\"respawnAvailableAtMs\":0,\"deathCauseKind\":\"FALL\"}";

    @TempDir
    Path temp;
    private Path data;
    private Path scratch;

    @BeforeEach
    void folders() throws IOException {
        data = Files.createDirectories(temp.resolve("Data"));
        scratch = temp.resolve("Companions.importing").resolve("legacy");
    }

    @Test
    void aVersion1StateFileReadsEveryDomainTable() throws Exception {
        buildState("state-v1.sql", history(1, HASH));
        assertDomainRows(LegacyRows.StateSchema.V1);
    }

    @Test
    void aRoutedVersion2StateFileReadsTheSameRows() throws Exception {
        buildState("state-routed-v2.sql", history(2, ROUTED_HASH));
        assertDomainRows(LegacyRows.StateSchema.ROUTED_V2);
    }

    @Test
    void aVersion2StateFileReadsTheSameRows() throws Exception {
        buildState("state-v2.sql", history(1, HASH), history(2, HASH));
        assertDomainRows(LegacyRows.StateSchema.V2);
    }

    @Test
    void theBondedFileReadsItsRows() throws Exception {
        build(data.resolve(LegacySource.BONDED_FILE), "bonded-v1.sql", "bonded-data.sql");

        LegacyRows rows = LegacyReader.read(List.of(data), scratch);

        assertNull(rows.state(), "a missing state file is fine");
        LegacyRows.Bonded bonded = rows.bonded();
        assertNotNull(bonded);
        assertEquals(data.resolve(LegacySource.BONDED_FILE), bonded.source().path());
        String envelope = "{\"encoding\":\"base64\",\"payload\":\"e30=\"}";
        assertEquals(List.of(
                new LegacyRows.BondedProfile("b-active", OWNER, "dragons", "fire", "Bonded_Dragon", "ACTIVE", 6,
                        envelope, 100, 600, "{\"maxActive\":1}", "Ember", "dragon", "FEMALE", null, 0, 0,
                        null, null),
                new LegacyRows.BondedProfile("b-dead", OWNER, "dragons", "frost", "Bonded_Dragon", "DEAD", 9,
                        envelope, 200, 700, "{}", null, null, null, -650L, -400, 2, "damaged", 690L)),
                bonded.profiles());
        assertEquals(List.of(new LegacyRows.BondedLease(
                "b-active", "cccccccc-0000-0000-0000-000000000001", "world-a", 550, 0, "LIVE")), bonded.leases());
        assertEquals(List.of(new LegacyRows.BondedExtension("b-active", "Alechilles:HyDragon",
                "{\"encoding\":\"base64\",\"payload\":\"eyJhIjoxfQ==\"}", 1952, 600)), bonded.extensionData());
        assertEquals(List.of(new LegacyRows.BondedCleanupTarget("cleanup-1", "b-dead", "PROJECTION",
                "cccccccc-0000-0000-0000-000000000002", "PENDING", "world-a")), bonded.cleanupTargets());
        assertEquals(List.of(new LegacyRows.BondedCaptureSource(
                "b-active", "cccccccc-0000-0000-0000-000000000003", "world-b")), bonded.captureSources());
    }

    @Test
    void anUnknownSchemaVersionIsRefused() throws Exception {
        String looseHistory = "CREATE TABLE schema_history (version INTEGER PRIMARY KEY, lineage TEXT,"
                + " applied_at_ms INTEGER, schema_hash TEXT)";
        Path state = data.resolve(LegacySource.STATE_FILE);
        execute(state, looseHistory, history(3, HASH));
        Map<String, String> before = listing(data);

        LegacySource.Refused newer = assertThrows(LegacySource.Refused.class,
                () -> LegacyReader.read(List.of(data), scratch));
        assertEquals(LegacySource.Reason.UNKNOWN_VERSION, newer.reason());
        assertEquals(state, newer.file());
        assertEquals(before, listing(data));
        assertEquals(Map.of(), listing(scratch), "the private copy is deleted after a refusal");

        Files.delete(state);
        execute(state, looseHistory);
        assertEquals(LegacySource.Reason.UNKNOWN_VERSION, assertThrows(LegacySource.Refused.class,
                () -> LegacyReader.read(List.of(data), scratch)).reason(), "an empty history is not a known version");

        Files.delete(state);
        Path bonded = data.resolve(LegacySource.BONDED_FILE);
        execute(bonded, "CREATE TABLE bonded_schema_history (version INTEGER PRIMARY KEY, lineage TEXT)",
                "INSERT INTO bonded_schema_history VALUES (2, 'bonded-companions')");
        assertEquals(LegacySource.Reason.UNKNOWN_VERSION, assertThrows(LegacySource.Refused.class,
                () -> LegacyReader.read(List.of(data), scratch)).reason());
    }

    @Test
    void aFileThatIsNotADatabaseIsRefusedAndLeftAlone() throws Exception {
        Path state = data.resolve(LegacySource.STATE_FILE);
        Files.write(state, "this is not a database, only some text that is long enough".repeat(40)
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> before = listing(data);

        LegacySource.Refused refused = assertThrows(LegacySource.Refused.class,
                () -> LegacyReader.read(List.of(data), scratch));

        assertEquals(LegacySource.Reason.UNREADABLE, refused.reason());
        assertEquals(before, listing(data));
        assertEquals(Map.of(), listing(scratch));
    }

    @Test
    void aBackupNamedFileIsNeverChosen() throws Exception {
        Path backup = data.resolve("tamework-state.sqlite.v1-backup.2a99ac03-5081-441d-9657-e6d19daec352.sqlite");
        build(backup, "state-v1.sql", "state-data.sql");
        execute(backup, history(1, HASH));

        assertEquals(new LegacyRows(null, null), LegacyReader.read(List.of(data), scratch));
        assertThrows(LegacySource.Refused.class, () -> LegacyReader.readState(backup, scratch));

        // With the real file beside it, the real file is the one read.
        execute(data.resolve(LegacySource.STATE_FILE), read("state-v2.sql"));
        execute(data.resolve(LegacySource.STATE_FILE), history(2, HASH));
        LegacyRows.State state = LegacyReader.read(List.of(data), scratch).state();
        assertNotNull(state);
        assertEquals(LegacyRows.StateSchema.V2, state.schema());
        assertEquals(List.of(), state.profiles());
    }

    /** Review Focus 3: a crashed 4.x server leaves committed rows only in the write-ahead log. */
    @Test
    void anUncheckpointedWalIsReadWithoutTouchingTheOriginalFolder() throws Exception {
        Path live = Files.createDirectories(temp.resolve("live")).resolve(LegacySource.STATE_FILE);
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + live);
             Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            run(statement, read("state-v2.sql"), history(2, HASH), read("state-data.sql"));
            // What a killed server leaves behind: the main file and its log, never checkpointed.
            Files.copy(live, data.resolve(LegacySource.STATE_FILE));
            Files.copy(live.resolveSibling(LegacySource.STATE_FILE + "-wal"),
                    data.resolve(LegacySource.STATE_FILE + "-wal"));
        }
        Map<String, String> before = listing(data);
        assertEquals(2, before.size());

        LegacyRows.State state = LegacyReader.read(List.of(data), scratch).state();

        assertNotNull(state);
        assertEquals(List.of("p-coop", "p-dead", "p-live", "p-prov"),
                state.profiles().stream().map(LegacyRows.Profile::profileId).toList());
        assertEquals(before, listing(data), "no file is added, changed or removed beside the original");
        assertEquals(Map.of(), listing(scratch), "the private copy is deleted after the read");
    }

    private void assertDomainRows(LegacyRows.StateSchema schema) throws Exception {
        Path empty = Files.createDirectories(temp.resolve("mods"));
        Map<String, String> before = listing(data);

        LegacyRows rows = LegacyReader.read(List.of(empty, data), scratch);

        assertNull(rows.bonded(), "a missing bonded file is fine");
        LegacyRows.State state = rows.state();
        assertNotNull(state);
        assertEquals(schema, state.schema());
        assertEquals(new LegacyRows.SourceFile(data.resolve(LegacySource.STATE_FILE),
                Files.size(data.resolve(LegacySource.STATE_FILE)),
                Files.getLastModifiedTime(data.resolve(LegacySource.STATE_FILE)).toMillis()), state.source());
        assertEquals(List.of(
                new LegacyRows.Profile("p-coop", "Hen", "Tamed_Chicken", null, "world-a", 3000, 3500, 3400, 0),
                new LegacyRows.Profile("p-dead", null, null, null, null, 2000, 2500, 2400, 0),
                new LegacyRows.Profile("p-live", "Rex", "Tamed_Wolf",
                        "{\"owner_name\":\"Alec\",\"custom_name\":\"Rex\"}", "world-a", 1000, 1500, 1400, 3),
                new LegacyRows.Profile("p-prov", "Drake", "Tamed_Drake", null, null, 4000, 4500, 4400, 0)),
                state.profiles());
        assertEquals(List.of(
                new LegacyRows.Lifecycle("p-coop", OWNER, "COOP", "COOP_SLOT", COOP_KEY, null, null, 4, null,
                        3400, null),
                new LegacyRows.Lifecycle("p-dead", null, "DEAD_REVIVABLE", "NONE", null, null, null, 2, null,
                        2400, null),
                new LegacyRows.Lifecycle("p-live", OWNER, "ACTIVE", "LIVE_ENTITY", NPC, "world-a", "world-home", 7,
                        null, -5000, null),
                new LegacyRows.Lifecycle("p-prov", OWNER, "PROVISIONED_DORMANT", "PROVISIONING", "prov-key", null,
                        null, 1, "op-open", 4400, "incident-1")),
                state.lifecycles());
        assertEquals(List.of(
                new LegacyRows.Alias(OLD_NPC, "p-live", 0, "RETIRED", 1000, 1200L),
                new LegacyRows.Alias(NPC, "p-live", 1, "CURRENT", 1200, null),
                new LegacyRows.Alias(LEASED_NPC, "p-prov", 0, "LEASED", 4400, null)),
                state.aliases());
        assertEquals(List.of(
                new LegacyRows.Snapshot("snap-coop", "p-coop", "coop", 1, "{\"roleId\":\"Tamed_Chicken\"}", 4, 3400),
                new LegacyRows.Snapshot("snap-dead", "p-dead", "death", 2, DEATH_JSON, 2, 2400)),
                state.currentSnapshots(), "only current snapshots are returned");
        assertEquals(List.of(new LegacyRows.EntityCheckpoint("p-live", "alias:" + NPC, CHECKPOINT_JSON, 9, 1480)),
                state.entityCheckpoints(), "a tombstoned checkpoint is skipped");
        assertEquals(List.of(new LegacyRows.ToolLink(
                "p-live", "bbbbbbbb-0000-0000-0000-000000000001", "COMMAND", 1300, 1350)), state.toolLinks());
        assertEquals(List.of(new LegacyRows.RosterFamily(OWNER, "wolves", 5, 1000, 1500)), state.rosterFamilies());
        assertEquals(List.of(
                new LegacyRows.RosterMembership("slot-1", "p-live", OWNER, "wolves", 2, "pack", true,
                        "world-home", 1.5, 64.0, -2.25, 1000, 1500),
                new LegacyRows.RosterMembership("slot-2", "p-prov", OWNER, "wolves", 1, null, false,
                        null, null, null, null, 4000, 4500)),
                state.rosterMemberships());
        assertEquals(List.of(
                new LegacyRows.TimedLease("p-live", 3, "session-1", 45000L, null, "Timed_Wolf", 60000, 30000, true,
                        1450L, 1000, 1450),
                new LegacyRows.TimedLease("p-prov", 1, null, null, -7000L, null, 60000, 30000, false,
                        null, 4000, 4500)),
                state.timedLeases());
        assertEquals(List.of(
                new LegacyRows.CoopSlot(COOP_KEY, "world-a", "coop", 10, 64, -20, 0, 2, null, null),
                new LegacyRows.CoopSlot("v1:d29ybGQtYQ:Y29vcA:10:64:-20:1", "world-a", "coop", 10, 64, -20, 1, 0,
                        null, null)),
                state.coopSlots());
        assertEquals(List.of(new LegacyRows.CoopResidency(COOP_KEY, "p-coop", null, "snap-coop", 3400, 3450)),
                state.coopResidencies());
        assertEquals(List.of(new LegacyRows.Provisioning("p-prov", "Alechilles:HyDragon", "drake-1", null, 4000)),
                state.provisioningRecords());
        assertEquals(List.of(new LegacyRows.ExtensionData(
                "p-live", "Mod:Thing", "k1", 1, "{\"level\":4}", 12, 1100, 1490)),
                state.extensionData(), "tombstones and entity checkpoints are not extension data");
        assertEquals(1, state.unfinishedOperations());
        assertEquals(1, state.quarantinedProfiles());

        assertEquals(before, listing(data), "the original folder is unchanged");
        assertEquals(Map.of(), listing(scratch), "the private copy is deleted after the read");
    }

    private void buildState(String ddl, String... historyRows) throws Exception {
        Path state = data.resolve(LegacySource.STATE_FILE);
        build(state, ddl, "state-data.sql");
        execute(state, historyRows);
    }

    private static String history(int version, String hash) {
        return "INSERT INTO schema_history (version, lineage, applied_at_ms, schema_hash) VALUES ("
                + version + ", 'tamework-state', 1, '" + hash + "')";
    }

    private static void build(Path file, String... fixtures) throws Exception {
        for (String fixture : fixtures) {
            execute(file, read(fixture));
        }
    }

    /** Runs scripts against a database file with an ordinary read-write connection, then closes it. */
    private static void execute(Path file, String... scripts) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            run(statement, scripts);
        }
    }

    private static void run(Statement statement, String... scripts) throws SQLException {
        for (String script : scripts) {
            for (String sql : script.split(";\\s*(\\R|$)")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    /** A fixture script with its comment lines removed. */
    private static String read(String fixture) throws IOException {
        try (InputStream stream = LegacyReaderTest.class.getResourceAsStream("/import-fixtures/" + fixture)) {
            assertNotNull(stream, fixture);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.startsWith("--")).reduce("", (a, b) -> a + b + "\n");
        }
    }

    /** File name to size and modified time, for every file in a folder (empty when it is missing). */
    private static Map<String, String> listing(Path folder) throws IOException {
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(folder)) {
            return files;
        }
        try (Stream<Path> children = Files.list(folder)) {
            for (Path child : children.toList()) {
                files.put(child.getFileName().toString(),
                        Files.size(child) + "@" + Files.getLastModifiedTime(child).toMillis());
            }
        }
        return files;
    }
}

package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Classifies legacy/public, replacement, and unreleased development SQLite lineages.
 *
 * <p>All inspection occurs against an owned backup produced from a read-only source connection.
 * Classification never creates or mutates a replacement target.</p>
 */
public final class LegacySourceClassifier {
    private static final Map<Integer, String> PUBLIC_MIGRATIONS = Map.of(
            2, "schema_v2",
            3, "schema_v3_api_profile_data",
            4, "schema_v4_coop_state_snapshot"
    );
    private static final String LEGACY_IMPORT_NAME = "legacy_dat_import_v2";
    private static final Set<String> V2_TABLES = Set.of(
            "schema_migrations", "npc_profiles", "npc_uuid_aliases", "npc_tool_links",
            "npc_snapshots", "coop_slots", "profile_states"
    );
    private static final Set<String> V3_TABLES = union(V2_TABLES, "api_profile_data");
    private static final Set<String> DEVELOPMENT_TABLES = Set.of(
            "npc_recovery_operations", "managed_coop_authority", "managed_coop_residents",
            "managed_coop_uuid_claims", "coop_lifecycle_operations", "coop_import_conflicts",
            "companion_population_state", "companion_population_operations",
            "persistence_incidents", "api_profile_data_operations", "capture_attempts",
            "companion_provisioning_operations", "command_family_rosters",
            "command_timed_summon_sessions", "paid_command_revival_operations"
    );
    private static final Map<String, Set<String>> V2_COLUMNS = Map.of(
            "schema_migrations", Set.of("version", "name", "applied_at_ms"),
            "npc_profiles", Set.of(
                    "profile_id", "current_npc_uuid", "owner_uuid", "display_name", "role_id",
                    "state_json", "state_hash", "last_world_name", "created_at_ms",
                    "updated_at_ms", "last_active_at_ms"
            ),
            "npc_uuid_aliases", Set.of(
                    "npc_uuid", "profile_id", "is_current", "mapped_at_ms"
            ),
            "npc_tool_links", Set.of(
                    "profile_id", "tool_uuid", "link_type", "created_at_ms", "updated_at_ms"
            ),
            "npc_snapshots", Set.of(
                    "snapshot_id", "profile_id", "snapshot_type", "snapshot_version",
                    "payload_json", "is_active", "created_at_ms"
            ),
            "coop_slots", Set.of(
                    "world_name", "coop_id", "x", "y", "z", "resident_slot", "profile_id",
                    "housed_npc_uuid", "last_released_npc_uuid", "captured_at_ms",
                    "released_at_ms", "updated_at_ms"
            ),
            "profile_states", Set.of(
                    "profile_id", "capture_active", "death_active", "lost_active",
                    "in_coop", "coop_key", "updated_at_ms"
            )
    );
    private static final Set<String> EXTENSION_COLUMNS = Set.of(
            "profile_id", "namespace", "data_key", "json_payload", "created_at_ms", "updated_at_ms"
    );

    private final SqliteReadOnlySnapshotter snapshotter;

    public LegacySourceClassifier() {
        this(new SqliteReadOnlySnapshotter());
    }

    LegacySourceClassifier(@Nonnull SqliteReadOnlySnapshotter snapshotter) {
        if (snapshotter == null) {
            throw new IllegalArgumentException("Source snapshotter is required");
        }
        this.snapshotter = snapshotter;
    }

    /** Classifies one source using a temporary consistent backup under {@code workspace}. */
    @Nonnull
    public LegacySourceClassification classify(@Nonnull Path sourcePath, @Nonnull Path workspace) {
        if (sourcePath == null || workspace == null) {
            throw new IllegalArgumentException("Source path and workspace are required");
        }
        Path source = sourcePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            return result(LegacySourceKind.NO_SOURCE, 0, "NO_SOURCE", Optional.empty(), Set.of());
        }
        try (SqliteReadOnlySnapshotter.Snapshot snapshot = snapshotter.create(source, workspace)) {
            return inspect(snapshot);
        } catch (Exception failure) {
            return result(
                    LegacySourceKind.MALFORMED,
                    0,
                    "SOURCE_SNAPSHOT_FAILED",
                    Optional.empty(),
                    Set.of()
            );
        }
    }

    private LegacySourceClassification inspect(SqliteReadOnlySnapshotter.Snapshot snapshot)
            throws Exception {
        try (Connection connection =
                     new SqliteConnectionFactory(snapshot.path()).openReadConnection()) {
            if (!checkReturnsOk(connection, "PRAGMA quick_check")
                    || !checkReturnsOk(connection, "PRAGMA integrity_check")) {
                return result(LegacySourceKind.MALFORMED, 0, "SQLITE_INTEGRITY_FAILED",
                        Optional.of(snapshot.fingerprint()), Set.of());
            }
            Set<String> tables = userTables(connection);
            if (tables.equals(SqliteSchemaV1Manager.requiredTables())) {
                return classifyReplacement(snapshot, tables);
            }
            if (!tables.contains("schema_migrations")) {
                if (!disjoint(tables, DEVELOPMENT_TABLES)) {
                    return result(LegacySourceKind.DEVELOPMENT_V5_TO_V9, 5,
                            "UNSUPPORTED_DEVELOPMENT_SCHEMA",
                            Optional.of(snapshot.fingerprint()), tables);
                }
                return result(LegacySourceKind.AMBIGUOUS, 0, "MIGRATION_HISTORY_MISSING",
                        Optional.of(snapshot.fingerprint()), tables);
            }
            if (!columns(connection, "schema_migrations")
                    .equals(V2_COLUMNS.get("schema_migrations"))) {
                return result(LegacySourceKind.AMBIGUOUS, 0, "MIGRATION_HISTORY_SHAPE_MISMATCH",
                        Optional.of(snapshot.fingerprint()), tables);
            }
            Map<Integer, String> migrations = migrations(connection);
            int schemaVersion = publicSchemaVersion(migrations);
            if (containsDevelopmentVersion(migrations)
                    || !disjoint(tables, DEVELOPMENT_TABLES)) {
                return result(LegacySourceKind.DEVELOPMENT_V5_TO_V9,
                        highestDevelopmentVersion(migrations), "UNSUPPORTED_DEVELOPMENT_SCHEMA",
                        Optional.of(snapshot.fingerprint()), tables);
            }
            if (!validPublicMigrations(migrations, schemaVersion)) {
                return result(LegacySourceKind.AMBIGUOUS, schemaVersion,
                        "PUBLIC_MIGRATION_HISTORY_AMBIGUOUS",
                        Optional.of(snapshot.fingerprint()), tables);
            }
            if (foreignKeyViolation(connection)) {
                return result(LegacySourceKind.MALFORMED, schemaVersion,
                        "PUBLIC_FOREIGN_KEY_VIOLATION",
                        Optional.of(snapshot.fingerprint()), tables);
            }
            return classifyPublicShape(connection, snapshot.fingerprint(), tables, schemaVersion);
        }
    }

    private LegacySourceClassification classifyReplacement(
            SqliteReadOnlySnapshotter.Snapshot snapshot,
            Set<String> tables
    ) {
        PersistenceReadResult<?> verification =
                new SqliteSchemaV1Manager(new SqliteConnectionFactory(snapshot.path())).verify();
        if (verification instanceof PersistenceReadResult.Found<?>) {
            return result(LegacySourceKind.REPLACEMENT_V1, 1, "REPLACEMENT_V1",
                    Optional.of(snapshot.fingerprint()), tables);
        }
        return result(LegacySourceKind.AMBIGUOUS, 1, "REPLACEMENT_LINEAGE_INVALID",
                Optional.of(snapshot.fingerprint()), tables);
    }

    private LegacySourceClassification classifyPublicShape(
            Connection connection,
            LegacySourceFingerprint fingerprint,
            Set<String> tables,
            int version
    ) throws Exception {
        Set<String> expectedTables = version == 2 ? V2_TABLES : V3_TABLES;
        if (!tables.equals(expectedTables) || !hasExpectedColumns(connection, version)) {
            return result(LegacySourceKind.AMBIGUOUS, version, "PUBLIC_SCHEMA_SHAPE_MISMATCH",
                    Optional.of(fingerprint), tables);
        }
        return switch (version) {
            case 2 -> result(LegacySourceKind.PUBLIC_V2, version, "PUBLIC_V2",
                    Optional.of(fingerprint), tables);
            case 3 -> result(LegacySourceKind.PUBLIC_V3, version, "PUBLIC_V3",
                    Optional.of(fingerprint), tables);
            case 4 -> result(LegacySourceKind.PUBLIC_V4, version, "PUBLIC_V4",
                    Optional.of(fingerprint), tables);
            default -> result(LegacySourceKind.AMBIGUOUS, version, "PUBLIC_VERSION_UNSUPPORTED",
                    Optional.of(fingerprint), tables);
        };
    }

    private boolean hasExpectedColumns(Connection connection, int version) throws Exception {
        for (Map.Entry<String, Set<String>> entry : V2_COLUMNS.entrySet()) {
            Set<String> expected = entry.getValue();
            if ("coop_slots".equals(entry.getKey()) && version == 4) {
                expected = union(expected, "state_snapshot_json");
            }
            if (!columns(connection, entry.getKey()).equals(expected)) {
                return false;
            }
        }
        return version == 2
                || columns(connection, "api_profile_data").equals(EXTENSION_COLUMNS);
    }

    private Map<Integer, String> migrations(Connection connection) throws Exception {
        HashMap<Integer, String> migrations = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version, name FROM schema_migrations ORDER BY version"
             )) {
            while (rows.next()) {
                migrations.put(rows.getInt(1), rows.getString(2));
            }
        }
        return Map.copyOf(migrations);
    }

    private int publicSchemaVersion(Map<Integer, String> migrations) {
        if (migrations.containsKey(4)) {
            return 4;
        }
        if (migrations.containsKey(3)) {
            return 3;
        }
        if (migrations.containsKey(2)) {
            return 2;
        }
        return 0;
    }

    private boolean validPublicMigrations(Map<Integer, String> migrations, int version) {
        if (version < 2 || version > 4) {
            return false;
        }
        for (Map.Entry<Integer, String> migration : migrations.entrySet()) {
            int key = migration.getKey();
            if (key == 2001) {
                if (!LEGACY_IMPORT_NAME.equals(migration.getValue())) {
                    return false;
                }
                continue;
            }
            if (key < 2 || key > version
                    || !PUBLIC_MIGRATIONS.get(key).equals(migration.getValue())) {
                return false;
            }
        }
        for (int required = 2; required <= version; required++) {
            if (!PUBLIC_MIGRATIONS.get(required).equals(migrations.get(required))) {
                return false;
            }
        }
        return true;
    }

    private boolean containsDevelopmentVersion(Map<Integer, String> migrations) {
        return migrations.keySet().stream().anyMatch(version -> version >= 5 && version <= 9);
    }

    private int highestDevelopmentVersion(Map<Integer, String> migrations) {
        return migrations.keySet().stream()
                .filter(version -> version >= 5 && version <= 9)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(5);
    }

    private boolean checkReturnsOk(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() && "ok".equalsIgnoreCase(rows.getString(1)) && !rows.next();
        }
    }

    private boolean foreignKeyViolation(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            return rows.next();
        }
    }

    private Set<String> userTables(Connection connection) throws Exception {
        HashSet<String> tables = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT name FROM sqlite_master
                     WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                     """)) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return Set.copyOf(tables);
    }

    private Set<String> columns(Connection connection, String table) throws Exception {
        HashSet<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rows.next()) {
                columns.add(rows.getString("name"));
            }
        }
        return Set.copyOf(columns);
    }

    private LegacySourceClassification result(
            LegacySourceKind kind,
            int version,
            String code,
            Optional<LegacySourceFingerprint> fingerprint,
            Set<String> tables
    ) {
        return new LegacySourceClassification(kind, version, code, fingerprint, tables);
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        return left.stream().noneMatch(right::contains);
    }

    private static Set<String> union(Set<String> values, String additional) {
        HashSet<String> result = new HashSet<>(values);
        result.add(additional);
        return Set.copyOf(result);
    }
}

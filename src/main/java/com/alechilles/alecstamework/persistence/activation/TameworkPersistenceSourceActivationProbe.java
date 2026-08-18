package com.alechilles.alecstamework.persistence.activation;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteConnectionFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Inspects legacy and historical source candidates without target mutation. */
final class TameworkPersistenceSourceActivationProbe {
    private static final String LEGACY_DATABASE =
            PersistenceFiles.LEGACY_DATABASE;
    private static final List<String> LEGACY_DAT_FILES = List.of(
            "CommandLinkedNpcCaptures.dat",
            "CommandLinkedNpcCoops.dat",
            "CommandLinkedNpcDeaths.dat",
            "CommandLinkedNpcLost.dat",
            "CoopResidentSnapshots.dat"
    );
    private static final Set<String> V2_TABLES = Set.of(
            "schema_migrations", "npc_profiles", "npc_uuid_aliases",
            "npc_tool_links", "npc_snapshots", "coop_slots",
            "profile_states"
    );
    private static final Set<String> V3_TABLES = union(
            V2_TABLES, "api_profile_data");
    private static final Map<Integer, String> PUBLIC_MIGRATIONS = Map.of(
            2, "schema_v2",
            3, "schema_v3_api_profile_data",
            4, "schema_v4_coop_state_snapshot"
    );

    private final List<Path> sourceDirectories;

    TameworkPersistenceSourceActivationProbe(List<Path> sourceDirectories) {
        if (sourceDirectories == null
                || sourceDirectories.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Persistence source directories are required");
        }
        LinkedHashSet<Path> normalized = new LinkedHashSet<>();
        sourceDirectories.forEach(path -> normalized.add(
                path.toAbsolutePath().normalize()));
        this.sourceDirectories = List.copyOf(normalized);
    }

    Disposition probe() {
        boolean importable = false;
        boolean uncertain = false;
        for (Path directory : sourceDirectories) {
            PathState directoryState = inspectPath(directory);
            if (directoryState == PathState.MISSING) {
                continue;
            }
            if (directoryState != PathState.DIRECTORY) {
                uncertain = true;
                continue;
            }
            SourceDisposition disposition = inspectSourceDirectory(directory);
            if (disposition == SourceDisposition.IMPORTABLE) {
                if (importable) {
                    uncertain = true;
                }
                importable = true;
            } else if (disposition == SourceDisposition.UNCERTAIN) {
                uncertain = true;
            }
        }
        if (uncertain) {
            return Disposition.UNCERTAIN;
        }
        return importable ? Disposition.IMPORTABLE : Disposition.NONE;
    }

    private SourceDisposition inspectSourceDirectory(Path directory) {
        Path legacy = directory.resolve(LEGACY_DATABASE);
        SidecarState sidecars = inspectSidecars(legacy);
        if (sidecars != SidecarState.NONE) {
            return SourceDisposition.UNCERTAIN;
        }
        PathState sqlite = inspectPath(legacy);
        if (sqlite == PathState.UNKNOWN
                || sqlite == PathState.DIRECTORY
                || sqlite == PathState.NON_REGULAR) {
            return SourceDisposition.UNCERTAIN;
        }
        boolean sqliteImportable = sqlite == PathState.REGULAR
                && importableLegacySqlite(legacy);
        if (sqlite == PathState.REGULAR && !sqliteImportable) {
            return SourceDisposition.UNCERTAIN;
        }

        boolean datPresent = false;
        for (String fileName : LEGACY_DAT_FILES) {
            Path dat = directory.resolve(fileName);
            PathState state = inspectPath(dat);
            if (state == PathState.UNKNOWN
                    || state == PathState.DIRECTORY
                    || state == PathState.NON_REGULAR) {
                return SourceDisposition.UNCERTAIN;
            }
            if (state == PathState.REGULAR) {
                datPresent = true;
                if (!validUtf8(dat)) {
                    return SourceDisposition.UNCERTAIN;
                }
            }
        }
        if (sqliteImportable && datPresent) {
            return SourceDisposition.UNCERTAIN;
        }
        return sqliteImportable || datPresent
                ? SourceDisposition.IMPORTABLE
                : SourceDisposition.NONE;
    }

    private boolean importableLegacySqlite(Path path) {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openImmutableSchemaProbeConnection()) {
            if (connection == null
                    || !singleValue(connection, "PRAGMA quick_check", "ok")
                    || !singleValue(connection, "PRAGMA integrity_check", "ok")
                    || hasRows(connection, "PRAGMA foreign_key_check")) {
                return false;
            }
            Set<String> tables = userTables(connection);
            if (!tables.equals(V2_TABLES) && !tables.equals(V3_TABLES)) {
                return false;
            }
            Map<Integer, String> migrations = migrations(connection);
            int version = migrations.containsKey(4) ? 4
                    : migrations.containsKey(3) ? 3 : 2;
            if (!validPublicMigrations(migrations, version)
                    || !hasLegacyColumns(connection, version)) {
                return false;
            }
            return version < 4 || hasColumn(
                    connection, "coop_slots", "state_snapshot_json");
        } catch (Exception failure) {
            return false;
        }
    }

    private boolean validPublicMigrations(
            Map<Integer, String> migrations, int version
    ) {
        if (version < 2 || version > 4) {
            return false;
        }
        for (Map.Entry<Integer, String> migration : migrations.entrySet()) {
            int key = migration.getKey();
            if (key == 2001) {
                if (!"legacy_dat_import_v2".equals(migration.getValue())) {
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
            if (!PUBLIC_MIGRATIONS.get(required).equals(
                    migrations.get(required))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasLegacyColumns(Connection connection, int version)
            throws SQLException {
        Map<String, Set<String>> expected = Map.of(
                "schema_migrations", Set.of("version", "name", "applied_at_ms"),
                "npc_profiles", Set.of("profile_id", "current_npc_uuid",
                        "owner_uuid", "display_name", "role_id", "state_json",
                        "state_hash", "last_world_name", "created_at_ms",
                        "updated_at_ms", "last_active_at_ms"),
                "npc_uuid_aliases", Set.of("npc_uuid", "profile_id",
                        "is_current", "mapped_at_ms"),
                "npc_tool_links", Set.of("profile_id", "tool_uuid",
                        "link_type", "created_at_ms", "updated_at_ms"),
                "npc_snapshots", Set.of("snapshot_id", "profile_id",
                        "snapshot_type", "snapshot_version", "payload_json",
                        "is_active", "created_at_ms"),
                "profile_states", Set.of("profile_id", "capture_active",
                        "death_active", "lost_active", "in_coop", "coop_key",
                        "updated_at_ms")
        );
        for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
            if (!columns(connection, entry.getKey()).equals(entry.getValue())) {
                return false;
            }
        }
        Set<String> coopColumns = Set.of("world_name", "coop_id", "x", "y",
                "z", "resident_slot", "profile_id", "housed_npc_uuid",
                "last_released_npc_uuid", "captured_at_ms", "released_at_ms",
                "updated_at_ms", "state_snapshot_json");
        Set<String> v2CoopColumns = new LinkedHashSet<>(coopColumns);
        v2CoopColumns.remove("state_snapshot_json");
        if (!columns(connection, "coop_slots").equals(
                version >= 4 ? coopColumns : v2CoopColumns)) {
            return false;
        }
        return version < 3 || columns(connection, "api_profile_data").equals(
                Set.of("profile_id", "namespace", "data_key", "json_payload",
                        "created_at_ms", "updated_at_ms"));
    }

    private Set<String> userTables(Connection connection) throws SQLException {
        LinkedHashSet<String> tables = new LinkedHashSet<>();
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

    private Map<Integer, String> migrations(Connection connection)
            throws SQLException {
        Map<Integer, String> migrations = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version, name FROM schema_migrations")) {
            while (rows.next()) {
                if (migrations.put(rows.getInt(1), rows.getString(2)) != null) {
                    throw new SQLException("legacy_migration_duplicate");
                }
            }
        }
        return Map.copyOf(migrations);
    }

    private Set<String> columns(Connection connection, String table)
            throws SQLException {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "PRAGMA table_info('" + table.replace("'", "''") + "')")) {
            while (rows.next()) {
                columns.add(rows.getString("name"));
            }
        }
        return Set.copyOf(columns);
    }

    private boolean hasColumn(Connection connection, String table, String column)
            throws SQLException {
        return columns(connection, table).contains(column);
    }

    private boolean validUtf8(Path path) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(path)));
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private SidecarState inspectSidecars(Path database) {
        Path fileName = database.getFileName();
        Path parent = database.getParent();
        if (fileName == null || parent == null) {
            return SidecarState.NONE;
        }
        SidecarState result = SidecarState.NONE;
        for (String suffix : List.of("-wal", "-shm")) {
            PathState state = inspectPath(parent.resolve(fileName + suffix));
            if (state == PathState.UNKNOWN) {
                return SidecarState.UNKNOWN;
            }
            if (state == PathState.DIRECTORY
                    || state == PathState.NON_REGULAR) {
                return SidecarState.INVALID;
            }
            if (state == PathState.REGULAR) {
                result = SidecarState.PRESENT;
            }
        }
        return result;
    }

    private PathState inspectPath(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) {
                return PathState.DIRECTORY;
            }
            return attributes.isRegularFile()
                    ? PathState.REGULAR : PathState.NON_REGULAR;
        } catch (NoSuchFileException missing) {
            return PathState.MISSING;
        } catch (IOException | SecurityException failure) {
            return PathState.UNKNOWN;
        }
    }

    private boolean hasRows(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next();
        }
    }

    private boolean singleValue(Connection connection, String sql, String value)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() && value.equalsIgnoreCase(rows.getString(1))
                    && !rows.next();
        }
    }

    private static Set<String> union(Set<String> source, String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>(source);
        result.add(value);
        return Set.copyOf(result);
    }

    enum Disposition {
        NONE,
        IMPORTABLE,
        UNCERTAIN
    }

    private enum PathState {
        MISSING,
        DIRECTORY,
        REGULAR,
        NON_REGULAR,
        UNKNOWN
    }

    private enum SidecarState {
        NONE,
        PRESENT,
        INVALID,
        UNKNOWN
    }

    private enum SourceDisposition {
        NONE,
        IMPORTABLE,
        UNCERTAIN
    }
}

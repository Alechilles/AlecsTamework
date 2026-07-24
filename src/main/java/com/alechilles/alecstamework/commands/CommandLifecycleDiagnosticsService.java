package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.api.CommandFamilyRosterView;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Bounded, read-only diagnostics for command-family roster and timed summon state. */
final class CommandLifecycleDiagnosticsService {
    private static final int MAX_ROWS = 8;
    private static final int MAX_FIELD = 96;
    private final TameworkPersistenceRuntime persistence;
    private final SqliteConnectionManager connections;

    CommandLifecycleDiagnosticsService(@Nonnull TameworkPersistenceRuntime persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.connections = new SqliteConnectionManager(persistence.collectDiagnostics().databasePath());
    }

    @Nonnull
    List<String> overview() {
        return List.of(summary("Command-family rosters", "command_family_roster_memberships",
                        "active_for_bulk_commands"),
                stateSummary("Timed summons", "command_timed_summon_sessions", "summon_state",
                        "command_timed_summon_operations", "operation_state"));
    }

    @Nonnull
    List<String> commandFamily(@Nullable String ownerText, @Nullable String familyId) {
        if (ownerText == null || ownerText.isBlank()) return List.of(overview().getFirst());
        final UUID owner;
        try {
            owner = UUID.fromString(ownerText.trim());
        } catch (IllegalArgumentException invalid) {
            return List.of("Command-family diagnostic requires an owner UUID.");
        }
        if (familyId != null && !familyId.isBlank()) {
            try {
                CommandFamilyRosterView roster = persistence.getCommandFamilyRosterRepository()
                        .find(owner, familyId.trim());
                if (roster == null) return List.of("Command-family roster not found.");
                long bulkActive = roster.memberships().stream()
                        .filter(value -> value.activeForBulkCommands()).count();
                return List.of("Command-family roster: owner=" + owner + ", family="
                        + bounded(roster.commandFamilyId()) + ", revision=" + roster.revision()
                        + ", memberships=" + roster.memberships().size()
                        + ", bulkCommandActive=" + bulkActive + ", updatedAtMs=" + roster.updatedAtMs());
            } catch (Exception failure) {
                return List.of(unavailable("Command-family", failure));
            }
        }
        return queryRows("""
                SELECT r.command_family_id, r.row_revision, r.updated_at_ms,
                       COUNT(m.profile_id) memberships,
                       COALESCE(SUM(CASE WHEN m.active_for_bulk_commands = 1 THEN 1 ELSE 0 END), 0)
                           bulk_active
                FROM command_family_rosters r
                LEFT JOIN command_family_roster_memberships m
                  ON m.owner_uuid = r.owner_uuid AND m.command_family_id = r.command_family_id
                WHERE r.owner_uuid = ?
                GROUP BY r.command_family_id, r.row_revision, r.updated_at_ms
                ORDER BY r.command_family_id LIMIT ?
                """, statement -> {
            statement.setString(1, owner.toString());
            statement.setInt(2, MAX_ROWS);
        }, result -> "Command-family roster: owner=" + owner + ", family="
                + bounded(result.getString("command_family_id")) + ", revision="
                + result.getLong("row_revision") + ", memberships=" + result.getLong("memberships")
                + ", bulkCommandActive=" + result.getLong("bulk_active")
                + ", updatedAtMs=" + result.getLong("updated_at_ms"),
                "No command-family rosters found for owner " + owner + ".");
    }

    @Nonnull
    List<String> timed(@Nullable String operationOrProfile) {
        if (operationOrProfile == null || operationOrProfile.isBlank()) return List.of(overview().get(1));
        String key = operationOrProfile.trim();
        List<String> operations = queryRows("""
                SELECT operation_id, owner_uuid, command_family_id, profile_id, operation_kind,
                       operation_state, result_state, reason, updated_at_ms
                FROM command_timed_summon_operations
                WHERE operation_id = ? OR profile_id = ?
                ORDER BY updated_at_ms DESC LIMIT ?
                """, statement -> {
            statement.setString(1, key);
            statement.setString(2, key);
            statement.setInt(3, MAX_ROWS);
        }, result -> "Timed summon operation: id=" + bounded(result.getString("operation_id"))
                + ", owner=" + bounded(result.getString("owner_uuid"))
                + ", family=" + bounded(result.getString("command_family_id"))
                + ", profile=" + bounded(result.getString("profile_id"))
                + ", kind=" + bounded(result.getString("operation_kind"))
                + ", state=" + bounded(result.getString("operation_state"))
                + ", result=" + boundedOrNone(result.getString("result_state"))
                + ", reason=" + boundedOrNone(result.getString("reason"))
                + ", updatedAtMs=" + result.getLong("updated_at_ms"), null);
        if (!operations.isEmpty()) return operations;
        return queryRows("""
                SELECT owner_uuid, command_family_id, profile_id, row_revision, summon_state,
                       summon_session_id, summon_remaining_ms, resummon_cooldown_until_ms,
                       active_operation_id, updated_at_ms
                FROM command_timed_summon_sessions WHERE profile_id = ? LIMIT ?
                """, statement -> {
            statement.setString(1, key);
            statement.setInt(2, MAX_ROWS);
        }, result -> "Timed summon session: owner=" + bounded(result.getString("owner_uuid"))
                + ", family=" + bounded(result.getString("command_family_id"))
                + ", profile=" + bounded(result.getString("profile_id"))
                + ", revision=" + result.getLong("row_revision")
                + ", state=" + bounded(result.getString("summon_state"))
                + ", session=" + boundedOrNone(result.getString("summon_session_id"))
                + ", remainingMs=" + nullableLong(result, "summon_remaining_ms")
                + ", cooldownUntilMs=" + result.getLong("resummon_cooldown_until_ms")
                + ", operation=" + boundedOrNone(result.getString("active_operation_id")),
                "No timed summon operation or session found for '" + bounded(key) + "'.");
    }

    private String summary(String label, String table, String booleanColumn) {
        try (Connection connection = connections.openConnection()) {
            if (!tableExists(connection, table)) return label + ": schema=unavailable";
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) total, COALESCE(SUM("
                         + booleanColumn + "), 0) selected FROM " + table)) {
                result.next();
                return label + ": memberships=" + result.getLong("total")
                        + ", bulkCommandActive=" + result.getLong("selected");
            }
        } catch (Exception failure) {
            return unavailable(label, failure);
        }
    }

    private String stateSummary(String label, String primaryTable, String primaryState,
                                String operationTable, String operationState) {
        try (Connection connection = connections.openConnection()) {
            if (!tableExists(connection, primaryTable)) return label + ": schema=unavailable";
            String states = countsByState(connection, primaryTable, primaryState);
            String operations = tableExists(connection, operationTable)
                    ? countsByState(connection, operationTable, operationState) : "{}";
            return label + ": states=" + states + ", operations=" + operations;
        } catch (Exception failure) {
            return unavailable(label, failure);
        }
    }

    private static String countsByState(Connection connection, String table, String column) throws Exception {
        ArrayList<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + column + " state, COUNT(*) total FROM "
                     + table + " GROUP BY " + column + " ORDER BY " + column)) {
            while (result.next()) values.add(result.getString("state") + "=" + result.getLong("total"));
        }
        return values.toString().replace('[', '{').replace(']', '}');
    }

    private List<String> queryRows(String sql, Binder binder, RowFormatter formatter,
                                   @Nullable String emptyMessage) {
        ArrayList<String> lines = new ArrayList<>();
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next() && lines.size() < MAX_ROWS) lines.add(formatter.format(result));
            }
        } catch (Exception failure) {
            return List.of(unavailable("Lifecycle diagnostic", failure));
        }
        if (lines.isEmpty() && emptyMessage != null) return List.of(emptyMessage);
        return List.copyOf(lines);
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static String nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? "<none>" : Long.toString(value);
    }

    private static String unavailable(String label, Throwable failure) {
        return label + " diagnostic unavailable: " + failure.getClass().getSimpleName();
    }

    private static String boundedOrNone(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : bounded(value);
    }

    private static String bounded(String value) {
        String clean = Objects.toString(value, "<none>").replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= MAX_FIELD ? clean : clean.substring(0, MAX_FIELD - 3) + "...";
    }

    @FunctionalInterface private interface Binder { void bind(PreparedStatement statement) throws Exception; }
    @FunctionalInterface private interface RowFormatter { String format(ResultSet result) throws Exception; }
}

package com.alechilles.alecstamework.companion.migrate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads a 3.x/4.x world's companion databases into {@link LegacyRows} (plan 7 R1). The domain
 * tables are the same in replacement v1, routed v2 and v2, so one set of queries serves all
 * three. Nothing is interpreted here; mapping rows to records is the importer's next step.
 */
public final class LegacyReader {
    /** The namespace the old runtime used for per-alias entity checkpoints. */
    public static final String ENTITY_CHECKPOINT_NAMESPACE = "Alechilles:Tamework:EntityCheckpoint";

    private LegacyReader() {
    }

    /**
     * Reads whichever old files exist in {@code legacyDirs}. A part is {@code null} when its file
     * is missing. Any refused file fails the whole read, so no partial import can follow (R2).
     *
     * @param scratchDir a folder the importer owns; the private copies live there during the read
     */
    @Nonnull
    public static LegacyRows read(@Nonnull Collection<Path> legacyDirs, @Nonnull Path scratchDir)
            throws LegacySource.Refused {
        LegacySource.Located located = LegacySource.locate(legacyDirs);
        return new LegacyRows(
                located.stateFile() == null ? null : readState(located.stateFile(), scratchDir),
                located.bondedFile() == null ? null : readBonded(located.bondedFile(), scratchDir));
    }

    /** Reads one {@code tamework-state.sqlite}. */
    @Nonnull
    public static LegacyRows.State readState(@Nonnull Path stateFile, @Nonnull Path scratchDir)
            throws LegacySource.Refused {
        try (LegacySource source = LegacySource.open(stateFile, scratchDir)) {
            LegacyRows.StateSchema schema = source.stateSchema();
            try {
                return state(source, schema);
            } catch (SQLException failure) {
                throw unreadable(stateFile, failure);
            }
        }
    }

    /** Reads one {@code bonded-companions.sqlite}. */
    @Nonnull
    public static LegacyRows.Bonded readBonded(@Nonnull Path bondedFile, @Nonnull Path scratchDir)
            throws LegacySource.Refused {
        try (LegacySource source = LegacySource.open(bondedFile, scratchDir)) {
            source.requireBondedSchema();
            try {
                return bonded(source);
            } catch (SQLException failure) {
                throw unreadable(bondedFile, failure);
            }
        }
    }

    private static LegacySource.Refused unreadable(Path file, SQLException failure) {
        return new LegacySource.Refused(
                LegacySource.Reason.UNREADABLE, file, String.valueOf(failure.getMessage()), failure);
    }

    private static LegacyRows.State state(LegacySource source, LegacyRows.StateSchema schema) throws SQLException {
        Connection db = source.connection();
        return new LegacyRows.State(
                source.original(),
                schema,
                query(db, """
                        SELECT profile_id, display_name, role_id, metadata_json, last_known_world_key,
                               created_at_ms, updated_at_ms, last_active_at_ms, metadata_revision
                        FROM companion_profile ORDER BY profile_id
                        """, r -> new LegacyRows.Profile(
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getLong(6), r.getLong(7), r.getLong(8), r.getLong(9))),
                query(db, """
                        SELECT profile_id, owner_uuid, lifecycle_state, location_kind, location_key,
                               world_key, owner_world_key, revision, active_operation_id,
                               state_changed_at_ms, quarantine_incident_id
                        FROM companion_lifecycle ORDER BY profile_id
                        """, r -> new LegacyRows.Lifecycle(
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getString(7), r.getLong(8), r.getString(9),
                        r.getLong(10), r.getString(11))),
                query(db, """
                        SELECT npc_uuid, profile_id, alias_generation, alias_state, mapped_at_ms, retired_at_ms
                        FROM companion_alias ORDER BY npc_uuid
                        """, r -> new LegacyRows.Alias(
                        r.getString(1), r.getString(2), r.getLong(3), r.getString(4), r.getLong(5),
                        nullableLong(r, 6))),
                query(db, """
                        SELECT snapshot_id, profile_id, snapshot_kind, payload_version, payload_json,
                               source_lifecycle_revision, created_at_ms
                        FROM companion_snapshot WHERE is_current = 1 ORDER BY snapshot_id
                        """, r -> new LegacyRows.Snapshot(
                        r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5),
                        r.getLong(6), r.getLong(7))),
                query(db, """
                        SELECT profile_id, data_key, json_payload, revision, updated_at_ms
                        FROM profile_extension_data
                        WHERE deleted_at_ms IS NULL AND namespace = '%s'
                        ORDER BY profile_id, data_key
                        """.formatted(ENTITY_CHECKPOINT_NAMESPACE), r -> new LegacyRows.EntityCheckpoint(
                        r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getLong(5))),
                query(db, """
                        SELECT profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms
                        FROM companion_tool_link ORDER BY profile_id, tool_uuid, link_type
                        """, r -> new LegacyRows.ToolLink(
                        r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getLong(5))),
                query(db, """
                        SELECT owner_uuid, family_id, roster_revision, created_at_ms, updated_at_ms
                        FROM command_family ORDER BY owner_uuid, family_id
                        """, r -> new LegacyRows.RosterFamily(
                        r.getString(1), r.getString(2), r.getLong(3), r.getLong(4), r.getLong(5))),
                query(db, """
                        SELECT slot_id, profile_id, owner_uuid, family_id, membership_revision, group_id,
                               active_for_bulk_commands, home_world_key, home_x, home_y, home_z,
                               created_at_ms, updated_at_ms
                        FROM command_roster_membership ORDER BY slot_id
                        """, r -> new LegacyRows.RosterMembership(
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5),
                        r.getString(6), r.getInt(7) != 0, r.getString(8),
                        nullableDouble(r, 9), nullableDouble(r, 10), nullableDouble(r, 11),
                        r.getLong(12), r.getLong(13))),
                query(db, """
                        SELECT profile_id, lease_revision, session_id, remaining_ms, cooldown_until_ms,
                               config_id, active_duration_ms, resummon_cooldown_ms,
                               auto_store_on_owner_logout, checkpointed_at_ms, created_at_ms, updated_at_ms
                        FROM timed_summon_lease ORDER BY profile_id
                        """, r -> new LegacyRows.TimedLease(
                        r.getString(1), r.getLong(2), r.getString(3), nullableLong(r, 4), nullableLong(r, 5),
                        r.getString(6), r.getLong(7), r.getLong(8), r.getInt(9) != 0,
                        nullableLong(r, 10), r.getLong(11), r.getLong(12))),
                query(db, """
                        SELECT coop_key, world_key, coop_id, x, y, z, resident_slot, residency_revision,
                               active_operation_id, reserved_profile_id
                        FROM coop_slot ORDER BY coop_key
                        """, r -> new LegacyRows.CoopSlot(
                        r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5), r.getInt(6),
                        r.getInt(7), r.getLong(8), r.getString(9), r.getString(10))),
                query(db, """
                        SELECT coop_key, profile_id, housed_npc_uuid, snapshot_id, captured_at_ms, updated_at_ms
                        FROM coop_residency ORDER BY coop_key
                        """, r -> new LegacyRows.CoopResidency(
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getLong(5), r.getLong(6))),
                query(db, """
                        SELECT profile_id, caller_namespace, caller_key, correlation_id, created_at_ms
                        FROM provisioning_record ORDER BY profile_id
                        """, r -> new LegacyRows.Provisioning(
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5))),
                query(db, """
                        SELECT profile_id, namespace, data_key, payload_version, json_payload, revision,
                               created_at_ms, updated_at_ms
                        FROM profile_extension_data
                        WHERE deleted_at_ms IS NULL AND namespace <> '%s'
                        ORDER BY profile_id, namespace, data_key
                        """.formatted(ENTITY_CHECKPOINT_NAMESPACE), r -> new LegacyRows.ExtensionData(
                        r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5),
                        r.getLong(6), r.getLong(7), r.getLong(8))),
                count(db, """
                        SELECT COUNT(*) FROM operation_envelope
                        WHERE phase NOT IN ('PUBLISHED', 'COMPENSATED', 'FAILED')
                        """),
                count(db, "SELECT COUNT(*) FROM companion_lifecycle WHERE quarantine_incident_id IS NOT NULL"));
    }

    private static LegacyRows.Bonded bonded(LegacySource source) throws SQLException {
        Connection db = source.connection();
        return new LegacyRows.Bonded(
                source.original(),
                query(db, """
                        SELECT profile_id, owner_uuid, roster_id, family_id, role_id, state, revision,
                               snapshot_json, created_at_ms, updated_at_ms, policy_json, display_name,
                               species, gender, died_at_ms, summon_cooldown_until_ms, revive_count,
                               quarantine_reason, quarantined_at_ms
                        FROM bonded_companion_profile ORDER BY profile_id
                        """, r -> new LegacyRows.BondedProfile(
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getLong(7), r.getString(8), r.getLong(9), r.getLong(10),
                        r.getString(11), r.getString(12), r.getString(13), r.getString(14),
                        nullableLong(r, 15), r.getLong(16), r.getLong(17), r.getString(18),
                        nullableLong(r, 19))),
                query(db, """
                        SELECT profile_id, live_npc_uuid, world_key, started_at_ms, expires_at_ms, projection_state
                        FROM bonded_companion_lease ORDER BY profile_id
                        """, r -> new LegacyRows.BondedLease(
                        r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getLong(5),
                        r.getString(6))),
                query(db, """
                        SELECT profile_id, namespace, json_payload, revision, updated_at_ms
                        FROM bonded_companion_extension_data ORDER BY profile_id, namespace
                        """, r -> new LegacyRows.BondedExtension(
                        r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getLong(5))),
                query(db, """
                        SELECT cleanup_id, profile_id, target_kind, target_npc_uuid, cleanup_state, world_key
                        FROM bonded_companion_cleanup ORDER BY cleanup_id
                        """, r -> new LegacyRows.BondedCleanupTarget(
                        r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6))),
                query(db, """
                        SELECT profile_id, source_npc_uuid, source_world_key
                        FROM bonded_companion_capture_source ORDER BY profile_id
                        """, r -> new LegacyRows.BondedCaptureSource(
                        r.getString(1), r.getString(2), r.getString(3))));
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private static <T> List<T> query(Connection db, String sql, RowMapper<T> mapper) throws SQLException {
        List<T> rows = new ArrayList<>();
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(mapper.map(result));
            }
        }
        return List.copyOf(rows);
    }

    private static int count(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    @Nullable
    private static Long nullableLong(ResultSet row, int column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    @Nullable
    private static Double nullableDouble(ResultSet row, int column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }
}

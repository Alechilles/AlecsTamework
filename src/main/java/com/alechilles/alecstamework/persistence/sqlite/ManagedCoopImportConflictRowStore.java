package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter.QuarantineRows;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Writes one immutable unresolved quarantine record inside its source-binding savepoint. */
final class ManagedCoopImportConflictRowStore {
    void write(Connection connection, QuarantineRows rows, DispositionBinding binding)
            throws SQLException {
        if (!rows.binding().equals(binding)) {
            return;
        }
        Match existing = match(connection, rows, binding);
        if (existing != Match.ABSENT) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_import_conflicts (
                    conflict_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    conflict_kind, source_fingerprint, source_payload, resolution_state,
                    created_at_ms, resolved_at_ms, resolution_note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNRESOLVED', ?, 0, NULL)
                """)) {
            statement.setString(1, binding.conflictId());
            statement.setString(2, rows.authorityKey().authorityId());
            statement.setString(3, rows.authorityKey().worldName());
            statement.setString(4, rows.coopId());
            statement.setInt(5, rows.authorityKey().x());
            statement.setInt(6, rows.authorityKey().y());
            statement.setInt(7, rows.authorityKey().z());
            statement.setInt(8, rows.source().sourceSlot());
            statement.setString(9, binding.conflictKind());
            statement.setString(10, rows.source().sourceFingerprint());
            statement.setString(11, rows.source().sourcePayload());
            statement.setLong(12, binding.boundAtMs());
            statement.executeUpdate();
        }
    }

    private Match match(Connection connection,
                        QuarantineRows rows,
                        DispositionBinding binding) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT authority_id, world_name, coop_id, x, y, z, resident_slot,
                       conflict_kind, source_fingerprint, source_payload, resolution_state
                FROM coop_import_conflicts WHERE conflict_id = ? LIMIT 2
                """)) {
            query.setString(1, binding.conflictId());
            try (ResultSet resultSet = query.executeQuery()) {
                if (!resultSet.next()) {
                    return Match.ABSENT;
                }
                boolean same = rows.authorityKey().authorityId().equals(resultSet.getString("authority_id"))
                        && rows.authorityKey().worldName().equals(resultSet.getString("world_name"))
                        && rows.coopId().equalsIgnoreCase(resultSet.getString("coop_id"))
                        && rows.authorityKey().x() == resultSet.getInt("x")
                        && rows.authorityKey().y() == resultSet.getInt("y")
                        && rows.authorityKey().z() == resultSet.getInt("z")
                        && rows.source().sourceSlot() == resultSet.getInt("resident_slot")
                        && binding.conflictKind().equals(resultSet.getString("conflict_kind"))
                        && rows.source().sourceFingerprint().equals(
                        resultSet.getString("source_fingerprint"))
                        && rows.source().sourcePayload().equals(resultSet.getString("source_payload"))
                        && "UNRESOLVED".equals(resultSet.getString("resolution_state"));
                return same && !resultSet.next() ? Match.EXACT : Match.CONFLICT;
            }
        }
    }

    private enum Match { ABSENT, EXACT, CONFLICT }
}

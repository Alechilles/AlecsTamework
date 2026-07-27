package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionLeaseEvidenceUnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Read-only bounded lease queries used by projection maintenance and startup recovery. */
final class SqliteBondedCompanionLeaseReader {
    private final SqliteConnectionFactory connections;

    SqliteBondedCompanionLeaseReader(SqliteConnectionFactory connections) {
        this.connections = connections;
    }

    @Nonnull
    List<BondedCompanionProjectionValidator.LeaseExpectation> activeLeases(int limit) {
        return leases(null, null, null, null, limit);
    }

    @Nonnull
    List<BondedCompanionProjectionValidator.LeaseExpectation> liveLeasesAfter(
            @Nullable String afterProfileId, int limit
    ) {
        return leases(null, BondedCompanionProjectionValidator.LeasePhase.LIVE,
                null, afterProfileId, limit);
    }

    @Nonnull
    List<BondedCompanionProjectionValidator.LeaseExpectation> pendingLeasesBefore(
            long maximumLeaseRowId, @Nullable String afterProfileId, int limit
    ) {
        return leases(null, BondedCompanionProjectionValidator.LeasePhase.PENDING,
                maximumLeaseRowId, afterProfileId, limit);
    }

    long pendingLeaseHighWaterMark() {
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COALESCE((
                         SELECT seq FROM sqlite_sequence
                         WHERE name = 'bonded_companion_lease_admission'
                     ), 0)
                     """)) {
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (Exception failure) {
            throw new BondedCompanionLeaseEvidenceUnavailableException(
                    "startup high-water query", failure);
        }
    }

    @Nonnull
    List<BondedCompanionProjectionValidator.LeaseExpectation> findExpired(
            long nowMs, int limit
    ) {
        return leases(nowMs, null, null, null, limit);
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> leases(
            @Nullable Long expiredAt,
            @Nullable BondedCompanionProjectionValidator.LeasePhase phase,
            @Nullable Long maximumLeaseRowId,
            @Nullable String afterProfileId,
            int limit
    ) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        String expiry = expiredAt == null ? "WHERE p.state = 'ACTIVE'" : """
                WHERE p.state = 'ACTIVE' AND l.expires_at_ms != 0
                  AND l.expires_at_ms <= ?
                """;
        String phaseClause = phase == null ? "" : " AND l.projection_state = ?";
        String highWaterClause = maximumLeaseRowId == null
                ? "" : " AND a.admission_sequence <= ?";
        String cursorClause = afterProfileId == null ? "" : " AND l.profile_id > ?";
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT p.owner_uuid, p.roster_id, l.profile_id,
                            l.lease_token, l.live_npc_uuid, l.world_key,
                            l.started_at_ms, l.expires_at_ms, l.projection_state
                     FROM bonded_companion_lease l
                     JOIN bonded_companion_profile p ON p.profile_id = l.profile_id
                     JOIN bonded_companion_lease_admission a
                       ON a.profile_id = l.profile_id AND a.lease_token = l.lease_token
                     """ + expiry + phaseClause + highWaterClause + cursorClause + """
                     ORDER BY l.profile_id LIMIT ?
                     """)) {
            int index = 1;
            if (expiredAt != null) statement.setLong(index++, expiredAt);
            if (phase != null) statement.setString(index++, phase.name());
            if (maximumLeaseRowId != null) statement.setLong(index++, maximumLeaseRowId);
            if (afterProfileId != null) statement.setString(index++, afterProfileId);
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<BondedCompanionProjectionValidator.LeaseExpectation> result =
                        new ArrayList<>();
                while (rows.next()) result.add(new BondedCompanionProjectionValidator
                        .LeaseExpectation(UUID.fromString(rows.getString(1)), rows.getString(2),
                                rows.getString(3), rows.getString(4),
                                UUID.fromString(rows.getString(5)), rows.getString(6),
                                rows.getLong(7), rows.getLong(8),
                                BondedCompanionProjectionValidator.LeasePhase.valueOf(
                                        rows.getString(9))));
                return List.copyOf(result);
            }
        } catch (Exception failure) {
            throw new BondedCompanionLeaseEvidenceUnavailableException("lease query", failure);
        }
    }
}

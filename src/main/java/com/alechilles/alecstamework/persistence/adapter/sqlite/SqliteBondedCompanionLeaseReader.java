package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Exact world-, owner-, and marker-keyed bonded lease reads. */
final class SqliteBondedCompanionLeaseReader {
    private static final String SELECT = """
            SELECT p.owner_uuid, p.roster_id, l.profile_id,
                   l.lease_token, l.live_npc_uuid, l.world_key,
                   l.started_at_ms, l.expires_at_ms, l.projection_state
            FROM bonded_companion_lease l
            JOIN bonded_companion_profile p ON p.profile_id = l.profile_id
            WHERE p.state = 'ACTIVE'
            """;
    private final SqliteConnectionFactory connections;

    SqliteBondedCompanionLeaseReader(SqliteConnectionFactory connections) {
        this.connections = connections;
    }

    /** Compatibility read for diagnostics and test fixtures, never runtime recovery. */
    @Nonnull
    List<BondedCompanionProjectionValidator.LeaseExpectation> activeLeases(
            int limit
    ) {
        return query(SELECT + " ORDER BY l.profile_id LIMIT ?", statement ->
                statement.setInt(1, positive(limit)));
    }

    @Nonnull
    List<BondedCompanionProjectionValidator.LeaseExpectation> inWorld(
            String worldKey, int limit
    ) {
        return query(SELECT + """
                 AND l.world_key = ?
                 ORDER BY l.profile_id LIMIT ?
                """, statement -> {
            statement.setString(1, worldKey);
            statement.setInt(2, positive(limit));
        });
    }

    @Nonnull
    List<BondedCompanionProjectionValidator.LeaseExpectation> forOwner(
            UUID ownerUuid, int limit
    ) {
        return query(SELECT + """
                 AND p.owner_uuid = ?
                 ORDER BY l.world_key, l.profile_id LIMIT ?
                """, statement -> {
            statement.setString(1, ownerUuid.toString());
            statement.setInt(2, positive(limit));
        });
    }

    @Nonnull
    List<BondedCompanionProjectionValidator.LeaseExpectation> forOwnerInWorld(
            UUID ownerUuid, String worldKey, int limit
    ) {
        return query(SELECT + """
                 AND p.owner_uuid = ? AND l.world_key = ?
                 ORDER BY l.profile_id LIMIT ?
                """, statement -> {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, worldKey);
            statement.setInt(3, positive(limit));
        });
    }

    @Nonnull
    Optional<BondedCompanionProjectionValidator.LeaseExpectation> exact(
            String profileId, String leaseToken
    ) {
        List<BondedCompanionProjectionValidator.LeaseExpectation> result =
                query(SELECT + """
                     AND l.profile_id = ? AND l.lease_token = ?
                     LIMIT 1
                    """, statement -> {
                    statement.setString(1, profileId);
                    statement.setString(2, leaseToken);
                });
        return result.stream().findFirst();
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> query(
            String sql,
            Binder binder
    ) {
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<BondedCompanionProjectionValidator.LeaseExpectation>
                        result = new ArrayList<>();
                while (rows.next()) result.add(read(rows));
                return List.copyOf(result);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("bonded lease query failed", failure);
        }
    }

    private BondedCompanionProjectionValidator.LeaseExpectation read(
            ResultSet rows
    ) throws Exception {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                UUID.fromString(rows.getString(1)), rows.getString(2),
                rows.getString(3), rows.getString(4),
                UUID.fromString(rows.getString(5)), rows.getString(6),
                rows.getLong(7), rows.getLong(8),
                BondedCompanionProjectionValidator.LeasePhase.valueOf(
                        rows.getString(9)));
    }

    private int positive(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return limit;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws Exception;
    }
}

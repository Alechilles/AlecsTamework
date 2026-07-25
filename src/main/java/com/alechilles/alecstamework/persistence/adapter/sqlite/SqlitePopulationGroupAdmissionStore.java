package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAdmission;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Focused canonical-count and shared-envelope group reservation store. */
final class SqlitePopulationGroupAdmissionStore {
    private static final String COLUMNS = """
            operation_id, profile_id, expected_lifecycle_revision,
            owner_uuid, group_id, scope_kind, owner_world_key,
            owned_delta, active_delta, snapshotted_max_owned,
            snapshotted_max_active, policy_revision, created_at_ms
            """;

    private final Connection connection;

    SqlitePopulationGroupAdmissionStore(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Population group admission connection is required"
            );
        }
        this.connection = connection;
    }

    PopulationGroupCounts counts(PopulationGroupBucket bucket) {
        require(bucket, "Population group bucket");
        long[] committed = committed(bucket);
        long[] pending = pending(bucket);
        return new PopulationGroupCounts(
                committed[0],
                committed[1],
                pending[0],
                pending[1]
        );
    }

    Optional<PopulationGroupReservation> find(
            OperationId operationId,
            PopulationGroupBucket bucket
    ) {
        require(operationId, "Operation ID");
        require(bucket, "Population group bucket");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + """
                         FROM population_group_reservation
                         WHERE operation_id = ? AND owner_uuid = ?
                           AND group_id = ? AND scope_kind = ?
                           AND owner_world_key = ?
                        """)) {
            statement.setString(1, operationId.toString());
            bindBucket(statement, bucket, 2);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(read(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_group_reservation_find", failure);
        }
    }

    List<PopulationGroupReservation> findByOperation(
            OperationId operationId
    ) {
        require(operationId, "Operation ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + """
                         FROM population_group_reservation
                         WHERE operation_id = ?
                         ORDER BY owner_uuid, group_id, scope_kind,
                                  owner_world_key
                        """)) {
            statement.setString(1, operationId.toString());
            ArrayList<PopulationGroupReservation> reservations =
                    new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    reservations.add(read(rows));
                }
            }
            return List.copyOf(reservations);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "population_group_reservation_find_operation",
                    failure
            );
        }
    }

    PopulationGroupAdmission reserve(
            PopulationGroupReservation reservation
    ) {
        require(reservation, "Population group reservation");
        PopulationGroupReservation existing = find(
                reservation.operationId(), reservation.bucket()
        ).orElse(null);
        if (existing != null) {
            return result(
                    existing.equals(reservation)
                            ? PopulationGroupAdmission.Status.ADMITTED
                            : PopulationGroupAdmission.Status.CONFLICT,
                    reservation
            );
        }
        if (!operationOwns(reservation)) {
            return result(
                    PopulationGroupAdmission.Status.CONFLICT,
                    reservation
            );
        }
        PopulationGroupCounts counts = counts(reservation.bucket());
        if (exceeds(
                counts.committedOwned(),
                counts.pendingOwned(),
                reservation.ownedDelta(),
                reservation.snapshottedMaxOwned()
        )) {
            return new PopulationGroupAdmission(
                    PopulationGroupAdmission.Status.OWNED_CAPACITY_REACHED,
                    reservation,
                    counts
            );
        }
        if (exceeds(
                counts.committedActive(),
                counts.pendingActive(),
                reservation.activeDelta(),
                reservation.snapshottedMaxActive()
        )) {
            return new PopulationGroupAdmission(
                    PopulationGroupAdmission.Status.ACTIVE_CAPACITY_REACHED,
                    reservation,
                    counts
            );
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO population_group_reservation(
                    operation_id, profile_id, expected_lifecycle_revision,
                    owner_uuid, group_id, scope_kind, owner_world_key,
                    owned_delta, active_delta, snapshotted_max_owned,
                    snapshotted_max_active, policy_revision, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindReservation(statement, reservation);
            statement.executeUpdate();
            return new PopulationGroupAdmission(
                    PopulationGroupAdmission.Status.ADMITTED,
                    reservation,
                    counts
            );
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return result(
                        PopulationGroupAdmission.Status.CONFLICT,
                        reservation
                );
            }
            throw storeFailure(
                    "population_group_reservation_insert",
                    failure
            );
        }
    }

    boolean retireExact(OperationId operationId, int expectedCount) {
        require(operationId, "Operation ID");
        if (expectedCount < 0) {
            throw new IllegalArgumentException(
                    "Expected group reservation count cannot be negative"
            );
        }
        if (findByOperation(operationId).size() != expectedCount) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM population_group_reservation
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            return statement.executeUpdate() == expectedCount;
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "population_group_reservation_retire",
                    failure
            );
        }
    }

    private long[] committed(PopulationGroupBucket bucket) {
        String world = bucket.scope() == PopulationGroupScope.GLOBAL
                ? ""
                : " AND lifecycle.owner_world_key = ?";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS owned_count,
                       COALESCE(SUM(CASE
                           WHEN lifecycle.lifecycle_state
                                IN ('ACTIVE', 'UNLOADED')
                           THEN 1 ELSE 0 END), 0) AS active_count
                FROM population_group_membership membership
                JOIN companion_lifecycle lifecycle
                  ON lifecycle.profile_id = membership.profile_id
                WHERE membership.group_id = ?
                  AND membership.scope_kind = ?
                  AND lifecycle.owner_uuid = ?
                  AND lifecycle.lifecycle_state <> 'RELEASED'
                """ + world)) {
            statement.setString(1, bucket.groupId());
            statement.setString(2, bucket.scope().name());
            statement.setString(3, bucket.ownerId().toString());
            if (bucket.scope() == PopulationGroupScope.PER_WORLD) {
                statement.setString(4, bucket.ownerWorldKey());
            }
            return pair(statement);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_group_committed_count", failure);
        }
    }

    private long[] pending(PopulationGroupBucket bucket) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(reservation.owned_delta), 0),
                       COALESCE(SUM(reservation.active_delta), 0)
                FROM population_group_reservation reservation
                JOIN operation_envelope envelope
                  ON envelope.operation_id = reservation.operation_id
                WHERE reservation.owner_uuid = ?
                  AND reservation.group_id = ?
                  AND reservation.scope_kind = ?
                  AND reservation.owner_world_key = ?
                  AND envelope.phase NOT IN (
                      'PUBLISHED', 'COMPENSATED', 'FAILED'
                  )
                """)) {
            bindBucket(statement, bucket, 1);
            return pair(statement);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_group_pending_count", failure);
        }
    }

    private boolean operationOwns(
            PopulationGroupReservation reservation
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT envelope.expected_lifecycle_revision, envelope.phase,
                       EXISTS(
                           SELECT 1 FROM operation_participant
                           WHERE operation_id = envelope.operation_id
                             AND scope_type = 'PROFILE' AND scope_key = ?
                       ) AS owns_profile,
                       EXISTS(
                           SELECT 1 FROM operation_participant
                           WHERE operation_id = envelope.operation_id
                             AND scope_type = 'OWNER' AND scope_key = ?
                       ) AS owns_owner
                FROM operation_envelope envelope
                WHERE operation_id = ?
                """)) {
            statement.setString(1, reservation.profileId().toString());
            statement.setString(
                    2, reservation.bucket().ownerId().toString()
            );
            statement.setString(3, reservation.operationId().toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        && row.getInt("owns_profile") == 1
                        && row.getInt("owns_owner") == 1
                        && !OperationPhase.valueOf(
                        row.getString("phase")
                ).isTerminal()
                        && java.util.Objects.equals(
                        nullableLong(
                                row,
                                "expected_lifecycle_revision"
                        ),
                        reservation.expectedLifecycleRevision() == null
                                ? null
                                : reservation
                                .expectedLifecycleRevision().value()
                );
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "population_group_reservation_validate_operation",
                    failure
            );
        }
    }

    private void bindReservation(
            PreparedStatement statement,
            PopulationGroupReservation reservation
    ) throws SQLException {
        statement.setString(1, reservation.operationId().toString());
        statement.setString(2, reservation.profileId().toString());
        if (reservation.expectedLifecycleRevision() == null) {
            statement.setNull(3, java.sql.Types.BIGINT);
        } else {
            statement.setLong(
                    3, reservation.expectedLifecycleRevision().value()
            );
        }
        bindBucket(statement, reservation.bucket(), 4);
        statement.setInt(8, reservation.ownedDelta());
        statement.setInt(9, reservation.activeDelta());
        statement.setInt(10, reservation.snapshottedMaxOwned());
        statement.setInt(11, reservation.snapshottedMaxActive());
        statement.setLong(12, reservation.policyRevision());
        statement.setLong(13, reservation.createdAtMs());
    }

    private void bindBucket(
            PreparedStatement statement,
            PopulationGroupBucket bucket,
            int start
    ) throws SQLException {
        statement.setString(start, bucket.ownerId().toString());
        statement.setString(start + 1, bucket.groupId());
        statement.setString(start + 2, bucket.scope().name());
        statement.setString(start + 3, bucket.storedWorldKey());
    }

    private PopulationGroupReservation read(ResultSet row)
            throws SQLException {
        PopulationGroupScope scope = PopulationGroupScope.valueOf(
                row.getString("scope_kind")
        );
        PopulationGroupBucket bucket = new PopulationGroupBucket(
                OwnerId.parse(row.getString("owner_uuid")),
                row.getString("group_id"),
                scope,
                scope == PopulationGroupScope.GLOBAL
                        ? null
                        : row.getString("owner_world_key")
        );
        Long expected = nullableLong(
                row, "expected_lifecycle_revision"
        );
        return new PopulationGroupReservation(
                OperationId.parse(row.getString("operation_id")),
                ProfileId.parse(row.getString("profile_id")),
                expected == null
                        ? null
                        : new LifecycleRevision(expected),
                bucket,
                row.getInt("owned_delta"),
                row.getInt("active_delta"),
                row.getInt("snapshotted_max_owned"),
                row.getInt("snapshotted_max_active"),
                row.getLong("policy_revision"),
                row.getLong("created_at_ms")
        );
    }

    private Long nullableLong(ResultSet row, String column)
            throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private PopulationGroupAdmission result(
            PopulationGroupAdmission.Status status,
            PopulationGroupReservation reservation
    ) {
        return new PopulationGroupAdmission(
                status, reservation, counts(reservation.bucket())
        );
    }

    private boolean exceeds(
            long committed,
            long pending,
            int delta,
            int limit
    ) {
        return delta > 0 && limit > 0
                && committed + pending + delta > limit;
    }

    private long[] pair(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SQLException("population_group_count_missing");
            }
            return new long[] {row.getLong(1), row.getLong(2)};
        }
    }

    private boolean constraint(SQLException failure) {
        return failure.getErrorCode() == 19
                || (failure.getMessage() != null
                && failure.getMessage().toLowerCase().contains("constraint"));
    }

    private PersistenceStoreException storeFailure(
            String operation,
            Throwable failure
    ) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }

    private <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}


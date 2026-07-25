package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmission;
import com.alechilles.alecstamework.companion.population.OwnerPopulationPort;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReservation;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Connection-bound owner admission over canonical lifecycle and shared operation reservations.
 */
public final class SqliteOwnerPopulationStore implements OwnerPopulationPort {
    private static final String COLUMNS = """
            operation_id, profile_id, expected_lifecycle_revision, scope_kind,
            owner_uuid, owner_world_key, capacity_delta, snapshotted_limit,
            created_at_ms
            """;

    private final Connection connection;

    public SqliteOwnerPopulationStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Owner population store connection is required"
            );
        }
        this.connection = connection;
    }

    @Override
    public long committedCount(@Nonnull OwnerPopulationScope scope) {
        require(scope, "Population scope");
        String sql = scope.kind() == OwnerPopulationScope.Kind.GLOBAL
                ? """
                  SELECT COUNT(*) FROM companion_lifecycle
                  WHERE owner_uuid = ?
                  """
                : """
                  SELECT COUNT(*) FROM companion_lifecycle
                  WHERE owner_uuid = ? AND owner_world_key = ?
                  """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.ownerId().toString());
            if (scope.kind() == OwnerPopulationScope.Kind.PER_WORLD) {
                statement.setString(2, scope.ownerWorldKey());
            }
            return count(statement);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("owner_population_committed_count", failure);
        }
    }

    @Override
    public long pendingCount(@Nonnull OwnerPopulationScope scope) {
        require(scope, "Population scope");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(reservation.capacity_delta), 0)
                FROM owner_population_reservation reservation
                JOIN operation_envelope envelope
                  ON envelope.operation_id = reservation.operation_id
                WHERE reservation.scope_kind = ?
                  AND reservation.owner_uuid = ?
                  AND reservation.owner_world_key = ?
                  AND envelope.phase NOT IN ('PUBLISHED', 'COMPENSATED', 'FAILED')
                """)) {
            bindScope(statement, scope, 1);
            return count(statement);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("owner_population_pending_count", failure);
        }
    }

    @Override
    @Nonnull
    public Optional<OwnerPopulationReservation> find(
            @Nonnull OperationId operationId,
            @Nonnull OwnerPopulationScope scope
    ) {
        require(operationId, "Operation ID");
        require(scope, "Population scope");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + """
                        FROM owner_population_reservation
                        WHERE operation_id = ? AND scope_kind = ?
                          AND owner_uuid = ? AND owner_world_key = ?
                        """)) {
            statement.setString(1, operationId.toString());
            bindScope(statement, scope, 2);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(readReservation(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("owner_population_find_reservation", failure);
        }
    }

    @Override
    @Nonnull
    public List<OwnerPopulationReservation> findByOperation(
            @Nonnull OperationId operationId
    ) {
        require(operationId, "Operation ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + """
                        FROM owner_population_reservation
                        WHERE operation_id = ?
                        ORDER BY scope_kind, owner_uuid, owner_world_key
                        """)) {
            statement.setString(1, operationId.toString());
            ArrayList<OwnerPopulationReservation> reservations =
                    new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    reservations.add(readReservation(row));
                }
            }
            return List.copyOf(reservations);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "owner_population_find_operation_reservations",
                    failure
            );
        }
    }

    @Override
    @Nonnull
    public OwnerPopulationAdmission reserve(
            @Nonnull OwnerPopulationReservation reservation
    ) {
        require(reservation, "Population reservation");
        OwnerPopulationReservation existing = find(
                reservation.operationId(), reservation.scope()
        ).orElse(null);
        if (existing != null) {
            return admission(
                    existing.equals(reservation)
                            ? OwnerPopulationAdmission.Status.ADMITTED
                            : OwnerPopulationAdmission.Status.CONFLICT,
                    reservation
            );
        }
        if (!operationOwnsReservation(reservation)) {
            return admission(
                    OwnerPopulationAdmission.Status.CONFLICT,
                    reservation
            );
        }
        long committed = committedCount(reservation.scope());
        long pending = pendingCount(reservation.scope());
        if (capacityReached(reservation, committed, pending)) {
            return new OwnerPopulationAdmission(
                    OwnerPopulationAdmission.Status.CAPACITY_REACHED,
                    reservation,
                    committed,
                    pending
            );
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO owner_population_reservation(
                    operation_id, profile_id, expected_lifecycle_revision,
                    scope_kind, owner_uuid, owner_world_key, capacity_delta,
                    snapshotted_limit, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindReservation(statement, reservation);
            statement.executeUpdate();
            return new OwnerPopulationAdmission(
                    OwnerPopulationAdmission.Status.ADMITTED,
                    reservation,
                    committed,
                    pending
            );
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return admission(
                        OwnerPopulationAdmission.Status.CONFLICT,
                        reservation
                );
            }
            throw storeFailure("owner_population_reserve", failure);
        }
    }

    @Override
    public boolean retireExact(
            @Nonnull OperationId operationId,
            int expectedReservationCount
    ) {
        require(operationId, "Operation ID");
        if (expectedReservationCount < 0) {
            throw new IllegalArgumentException(
                    "Expected reservation count cannot be negative"
            );
        }
        if (findByOperation(operationId).size() != expectedReservationCount) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM owner_population_reservation
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            return statement.executeUpdate() == expectedReservationCount;
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("owner_population_retire", failure);
        }
    }

    private boolean operationOwnsReservation(
            OwnerPopulationReservation reservation
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT expected_lifecycle_revision, phase,
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
                    2,
                    reservation.scope().ownerId().toString()
            );
            statement.setString(3, reservation.operationId().toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()
                        || row.getInt("owns_profile") != 1
                        || row.getInt("owns_owner") != 1
                        || OperationPhase.valueOf(
                        row.getString("phase")
                ).isTerminal()) {
                    return false;
                }
                Long expected = nullableLong(
                        row,
                        "expected_lifecycle_revision"
                );
                LifecycleRevision revision =
                        reservation.expectedLifecycleRevision();
                return java.util.Objects.equals(
                        expected,
                        revision == null ? null : revision.value()
                );
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "owner_population_validate_operation",
                    failure
            );
        }
    }

    private OwnerPopulationAdmission admission(
            OwnerPopulationAdmission.Status status,
            OwnerPopulationReservation reservation
    ) {
        return new OwnerPopulationAdmission(
                status,
                reservation,
                committedCount(reservation.scope()),
                pendingCount(reservation.scope())
        );
    }

    private boolean capacityReached(
            OwnerPopulationReservation reservation,
            long committed,
            long pending
    ) {
        int limit = reservation.snapshottedLimit();
        if (limit == 0) {
            return false;
        }
        if (committed > limit || pending > limit) {
            return true;
        }
        long remaining = limit - committed - pending;
        return reservation.capacityDelta() > remaining;
    }

    private void bindReservation(
            PreparedStatement statement,
            OwnerPopulationReservation reservation
    ) throws SQLException {
        statement.setString(1, reservation.operationId().toString());
        statement.setString(2, reservation.profileId().toString());
        if (reservation.expectedLifecycleRevision() == null) {
            statement.setNull(3, Types.BIGINT);
        } else {
            statement.setLong(
                    3,
                    reservation.expectedLifecycleRevision().value()
            );
        }
        bindScope(statement, reservation.scope(), 4);
        statement.setInt(7, reservation.capacityDelta());
        statement.setInt(8, reservation.snapshottedLimit());
        statement.setLong(9, reservation.createdAtMs());
    }

    private void bindScope(
            PreparedStatement statement,
            OwnerPopulationScope scope,
            int start
    ) throws SQLException {
        statement.setString(start, scope.kind().name());
        statement.setString(start + 1, scope.ownerId().toString());
        statement.setString(start + 2, scope.storedWorldKey());
    }

    private OwnerPopulationReservation readReservation(ResultSet row)
            throws SQLException {
        Long expected = nullableLong(
                row,
                "expected_lifecycle_revision"
        );
        OwnerId owner = OwnerId.parse(row.getString("owner_uuid"));
        OwnerPopulationScope.Kind kind = OwnerPopulationScope.Kind.valueOf(
                row.getString("scope_kind")
        );
        OwnerPopulationScope scope = kind == OwnerPopulationScope.Kind.GLOBAL
                ? OwnerPopulationScope.global(owner)
                : OwnerPopulationScope.perWorld(
                        owner,
                        row.getString("owner_world_key")
                );
        return new OwnerPopulationReservation(
                OperationId.parse(row.getString("operation_id")),
                ProfileId.parse(row.getString("profile_id")),
                expected == null ? null : new LifecycleRevision(expected),
                scope,
                row.getInt("capacity_delta"),
                row.getInt("snapshotted_limit"),
                row.getLong("created_at_ms")
        );
    }

    private Long nullableLong(ResultSet row, String column)
            throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private long count(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SQLException("owner_population_count_missing");
            }
            return row.getLong(1);
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


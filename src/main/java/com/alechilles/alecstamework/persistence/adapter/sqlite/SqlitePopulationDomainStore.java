package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmission;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainBucket;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainCounts;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainPort;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
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
import javax.annotation.Nonnull;

/** Connection-bound weighted domain usage and reservation store. */
public final class SqlitePopulationDomainStore implements PopulationDomainPort {
    private static final String COLUMNS = """
            operation_id, profile_id, expected_lifecycle_revision,
            owner_uuid, domain_id, scope_kind, owner_world_key,
            owned_delta, deployable_delta, weight, snapshotted_max_owned,
            snapshotted_max_deployable, policy_revision, created_at_ms
            """;

    private final Connection connection;
    private final SqlitePopulationDomainConvergenceStore convergence;

    public SqlitePopulationDomainStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Domain store connection is required");
        }
        this.connection = connection;
        this.convergence = new SqlitePopulationDomainConvergenceStore(connection);
    }

    @Override
    @Nonnull
    public PopulationDomainCounts counts(@Nonnull PopulationDomainBucket bucket) {
        require(bucket, "Domain bucket");
        long[] committed = committed(bucket);
        long[] pending = pending(bucket);
        return new PopulationDomainCounts(
                committed[0], committed[1], pending[0], pending[1]
        );
    }

    @Override
    @Nonnull
    public Optional<PopulationDomainReservation> find(
            @Nonnull OperationId operationId,
            @Nonnull PopulationDomainBucket bucket
    ) {
        require(operationId, "Operation ID");
        require(bucket, "Domain bucket");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + """
                        FROM population_domain_reservation
                        WHERE operation_id = ? AND owner_uuid = ?
                          AND domain_id = ? AND scope_kind = ?
                          AND owner_world_key = ?
                        """)) {
            statement.setString(1, operationId.toString());
            bindBucket(statement, bucket, 2);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(SqlitePopulationDomainConvergenceStore.read(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_domain_reservation_find", failure);
        }
    }

    @Override
    @Nonnull
    public List<PopulationDomainReservation> findByOperation(
            @Nonnull OperationId operationId
    ) {
        require(operationId, "Operation ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + """
                        FROM population_domain_reservation
                        WHERE operation_id = ?
                        ORDER BY owner_uuid, domain_id, scope_kind, owner_world_key
                        """)) {
            statement.setString(1, operationId.toString());
            ArrayList<PopulationDomainReservation> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(SqlitePopulationDomainConvergenceStore.read(rows));
                }
            }
            return List.copyOf(result);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_domain_reservation_find_operation", failure);
        }
    }

    @Override
    @Nonnull
    public List<PopulationDomainReservation> findCommittedByProfile(
            @Nonnull ProfileId profileId
    ) {
        return convergence.findCommittedByProfile(profileId);
    }

    @Override
    @Nonnull
    public List<PopulationDomainReservation> findCommittedByProfileAndBucket(
            @Nonnull ProfileId profileId,
            @Nonnull PopulationDomainBucket bucket
    ) {
        return convergence.findCommittedByProfileAndBucket(profileId, bucket);
    }

    @Override
    @Nonnull
    public List<PopulationDomainReservation> findPendingByProfileAndBucket(
            @Nonnull ProfileId profileId,
            @Nonnull PopulationDomainBucket bucket
    ) {
        return convergence.findPendingByProfileAndBucket(profileId, bucket);
    }

    @Override
    @Nonnull
    public List<PopulationDomainReservation> findPendingByProfile(
            @Nonnull ProfileId profileId
    ) {
        return convergence.findPendingByProfile(profileId);
    }

    @Override
    @Nonnull
    public PopulationDomainAdmission reserve(
            @Nonnull PopulationDomainReservation reservation
    ) {
        require(reservation, "Domain reservation");
        PopulationDomainReservation existing = find(
                reservation.operationId(), reservation.bucket()
        ).orElse(null);
        if (existing != null) {
            if (!operationOwns(reservation)) {
                return result(PopulationDomainAdmission.Status.CONFLICT, reservation);
            }
            return result(
                    sameStoredReservation(existing, reservation)
                            ? PopulationDomainAdmission.Status.ADMITTED
                            : PopulationDomainAdmission.Status.CONFLICT,
                    reservation
            );
        }
        if (!operationOwns(reservation)) {
            return result(PopulationDomainAdmission.Status.CONFLICT, reservation);
        }
        PopulationDomainCounts counts = counts(reservation.bucket());
        if (exceeds(
                counts.committedOwned(),
                counts.pendingOwned(),
                reservation.weightedOwnedDelta(),
                reservation.snapshottedMaxOwned()
        )) {
            return new PopulationDomainAdmission(
                    PopulationDomainAdmission.Status.OWNED_CAPACITY_REACHED,
                    reservation,
                    counts
            );
        }
        if (exceeds(
                counts.committedDeployable(),
                counts.pendingDeployable(),
                reservation.weightedDeployableDelta(),
                reservation.snapshottedMaxDeployable()
        )) {
            return new PopulationDomainAdmission(
                    PopulationDomainAdmission.Status.DEPLOYABLE_CAPACITY_REACHED,
                    reservation,
                    counts
            );
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO population_domain_reservation(
                    operation_id, profile_id, expected_lifecycle_revision,
                    owner_uuid, domain_id, scope_kind, owner_world_key,
                    owned_delta, deployable_delta, weight,
                    snapshotted_max_owned, snapshotted_max_deployable,
                    policy_revision, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindReservation(statement, reservation);
            statement.executeUpdate();
            return new PopulationDomainAdmission(
                    PopulationDomainAdmission.Status.ADMITTED,
                    reservation,
                    counts
            );
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return result(PopulationDomainAdmission.Status.CONFLICT, reservation);
            }
            throw storeFailure("population_domain_reservation_insert", failure);
        }
    }

    @Override
    public boolean retireExact(
            @Nonnull OperationId operationId,
            int expectedReservationCount
    ) {
        require(operationId, "Operation ID");
        if (expectedReservationCount < 0) {
            throw new IllegalArgumentException("Expected domain reservation count cannot be negative");
        }
        if (findByOperation(operationId).size() != expectedReservationCount) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM population_domain_reservation WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            return statement.executeUpdate() == expectedReservationCount;
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_domain_reservation_retire", failure);
        }
    }

    @Override
    public boolean convergeExact(@Nonnull PopulationDomainConvergencePlan plan) {
        return convergence.convergeExact(plan);
    }

    /** Retains only the exact settled fraction of one aggregate reservation. */
    public boolean settleBatch(
            @Nonnull OperationId operationId,
            @Nonnull List<PopulationDomainReservation> expected,
            int requestedUnits,
            int settledUnits
    ) {
        require(operationId, "Operation ID");
        if (expected == null || requestedUnits <= 0
                || settledUnits < 0 || settledUnits > requestedUnits) {
            throw new IllegalArgumentException("Valid batch settlement is required");
        }
        if (findByOperation(operationId).size() != expected.size()) {
            return false;
        }
        for (PopulationDomainReservation reservation : expected) {
            int ownedPerUnit = perUnit(
                    reservation.ownedDelta(), requestedUnits
            );
            int deployablePerUnit = perUnit(
                    reservation.deployableDelta(), requestedUnits
            );
            int owned = Math.multiplyExact(ownedPerUnit, settledUnits);
            int deployable = Math.multiplyExact(deployablePerUnit, settledUnits);
            try {
                if (owned == 0 && deployable == 0) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            DELETE FROM population_domain_reservation
                            WHERE operation_id = ? AND owner_uuid = ?
                              AND domain_id = ? AND scope_kind = ?
                              AND owner_world_key = ?
                            """)) {
                        statement.setString(1, operationId.toString());
                        bindBucket(statement, reservation.bucket(), 2);
                        if (statement.executeUpdate() != 1) {
                            return false;
                        }
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE population_domain_reservation
                            SET owned_delta = ?, deployable_delta = ?
                            WHERE operation_id = ? AND owner_uuid = ?
                              AND domain_id = ? AND scope_kind = ?
                              AND owner_world_key = ?
                            """)) {
                        statement.setInt(1, owned);
                        statement.setInt(2, deployable);
                        statement.setString(3, operationId.toString());
                        bindBucket(statement, reservation.bucket(), 4);
                        if (statement.executeUpdate() != 1) {
                            return false;
                        }
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                throw storeFailure("population_domain_batch_settlement", failure);
            }
        }
        return true;
    }

    private int perUnit(int total, int requestedUnits) {
        if (total % requestedUnits != 0) {
            throw new IllegalArgumentException(
                    "Aggregate domain delta is not evenly divisible by requested units"
            );
        }
        return total / requestedUnits;
    }

    private long[] committed(PopulationDomainBucket bucket) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(CAST(reservation.weight AS INTEGER)
                                    * CAST(reservation.owned_delta AS INTEGER)), 0),
                       COALESCE(SUM(CAST(reservation.weight AS INTEGER)
                                    * CAST(reservation.deployable_delta AS INTEGER)), 0)
                FROM population_domain_reservation reservation
                JOIN operation_envelope envelope
                  ON envelope.operation_id = reservation.operation_id
                WHERE reservation.owner_uuid = ?
                  AND reservation.domain_id = ?
                  AND reservation.scope_kind = ?
                  AND reservation.owner_world_key = ?
                  AND envelope.phase IN ('DURABLE', 'PUBLISHED')
                """)) {
            bindBucket(statement, bucket, 1);
            return pair(statement);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_domain_committed_count", failure);
        }
    }

    private long[] pending(PopulationDomainBucket bucket) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(CAST(reservation.weight AS INTEGER)
                                    * CAST(reservation.owned_delta AS INTEGER)), 0),
                       COALESCE(SUM(CAST(reservation.weight AS INTEGER)
                                    * CAST(reservation.deployable_delta AS INTEGER)), 0)
                FROM population_domain_reservation reservation
                JOIN operation_envelope envelope
                  ON envelope.operation_id = reservation.operation_id
                WHERE reservation.owner_uuid = ?
                  AND reservation.domain_id = ?
                  AND reservation.scope_kind = ?
                  AND reservation.owner_world_key = ?
                  AND envelope.phase IN (
                      'PREPARED', 'LIVE_APPLYING', 'RETRYABLE',
                      'UNKNOWN', 'COMPENSATING'
                  )
                """)) {
            bindBucket(statement, bucket, 1);
            return pair(statement);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_domain_pending_count", failure);
        }
    }

    private boolean operationOwns(PopulationDomainReservation reservation) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT envelope.expected_lifecycle_revision, envelope.phase,
                       EXISTS(SELECT 1 FROM operation_participant
                              WHERE operation_id = envelope.operation_id
                                AND scope_type = 'PROFILE' AND scope_key = ?) AS owns_profile,
                       EXISTS(SELECT 1 FROM operation_participant
                              WHERE operation_id = envelope.operation_id
                                AND scope_type = 'OWNER' AND scope_key = ?) AS owns_owner
                FROM operation_envelope envelope
                WHERE operation_id = ?
                """)) {
            statement.setString(1, reservation.profileId().toString());
            statement.setString(2, reservation.bucket().ownerId().toString());
            statement.setString(3, reservation.operationId().toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getInt("owns_profile") != 1
                        || row.getInt("owns_owner") != 1
                        || OperationPhase.valueOf(row.getString("phase")).isTerminal()) {
                    return false;
                }
                Long expected = nullableLong(row, "expected_lifecycle_revision");
                LifecycleRevision revision = reservation.expectedLifecycleRevision();
                return java.util.Objects.equals(
                        expected,
                        revision == null ? null : revision.value()
                );
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_domain_validate_operation", failure);
        }
    }

    private void bindReservation(
            PreparedStatement statement,
            PopulationDomainReservation reservation
    ) throws SQLException {
        statement.setString(1, reservation.operationId().toString());
        statement.setString(2, reservation.profileId().toString());
        if (reservation.expectedLifecycleRevision() == null) {
            statement.setNull(3, java.sql.Types.BIGINT);
        } else {
            statement.setLong(3, reservation.expectedLifecycleRevision().value());
        }
        bindBucket(statement, reservation.bucket(), 4);
        statement.setInt(8, reservation.ownedDelta());
        statement.setInt(9, reservation.deployableDelta());
        statement.setInt(10, reservation.weight());
        statement.setInt(11, reservation.snapshottedMaxOwned());
        statement.setInt(12, reservation.snapshottedMaxDeployable());
        statement.setLong(13, reservation.policyRevision());
        statement.setLong(14, reservation.createdAtMs());
    }

    private void bindBucket(
            PreparedStatement statement,
            PopulationDomainBucket bucket,
            int start
    ) throws SQLException {
        statement.setString(start, bucket.ownerId().toString());
        statement.setString(start + 1, bucket.domainId());
        statement.setString(start + 2, bucket.scope().name());
        statement.setString(start + 3, bucket.storedWorldKey());
    }

    private PopulationDomainAdmission result(
            PopulationDomainAdmission.Status status,
            PopulationDomainReservation reservation
    ) {
        return new PopulationDomainAdmission(status, reservation, counts(reservation.bucket()));
    }

    /** The v2 table stores policy evidence but not provider/config revisions. */
    private boolean sameStoredReservation(
            PopulationDomainReservation stored,
            PopulationDomainReservation requested
    ) {
        return stored.operationId().equals(requested.operationId())
                && stored.profileId().equals(requested.profileId())
                && java.util.Objects.equals(
                        stored.expectedLifecycleRevision(),
                        requested.expectedLifecycleRevision()
                )
                && stored.bucket().equals(requested.bucket())
                && stored.ownedDelta() == requested.ownedDelta()
                && stored.deployableDelta() == requested.deployableDelta()
                && stored.weight() == requested.weight()
                && stored.snapshottedMaxOwned()
                        == requested.snapshottedMaxOwned()
                && stored.snapshottedMaxDeployable()
                        == requested.snapshottedMaxDeployable()
                && stored.policyRevision() == requested.policyRevision()
                && stored.createdAtMs() == requested.createdAtMs();
    }

    private boolean exceeds(long committed, long pending, long delta, int limit) {
        if (delta <= 0 || limit == 0) {
            return false;
        }
        return committed > limit || pending > limit
                || delta > (long) limit - committed - pending;
    }

    private long[] pair(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SQLException("population_domain_count_missing");
            }
            return new long[] {row.getLong(1), row.getLong(2)};
        }
    }

    private Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private boolean constraint(SQLException failure) {
        return failure.getErrorCode() == 19
                || (failure.getMessage() != null
                && failure.getMessage().toLowerCase().contains("constraint"));
    }

    private PersistenceStoreException storeFailure(String operation, Throwable failure) {
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

package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainBucket;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainReservation;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainScope;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Exact-row SQLite authority for retained population-domain convergence. */
final class SqlitePopulationDomainConvergenceStore {
    private static final String COLUMNS = """
            reservation.operation_id, reservation.profile_id,
            reservation.expected_lifecycle_revision,
            reservation.owner_uuid, reservation.domain_id, reservation.scope_kind,
            reservation.owner_world_key, reservation.owned_delta,
            reservation.deployable_delta, reservation.weight,
            reservation.snapshotted_max_owned,
            reservation.snapshotted_max_deployable, reservation.policy_revision,
            reservation.created_at_ms
            """;

    private static final String COMMITTED_PHASES = "IN ('DURABLE', 'PUBLISHED')";

    private final Connection connection;

    SqlitePopulationDomainConvergenceStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Domain convergence store connection is required"
            );
        }
        this.connection = connection;
    }

    @Nonnull
    List<PopulationDomainReservation> findCommittedByProfile(
            @Nonnull ProfileId profileId
    ) {
        return findRows(profileId, null, COMMITTED_PHASES);
    }

    @Nonnull
    List<PopulationDomainReservation> findCommittedByProfileAndBucket(
            @Nonnull ProfileId profileId,
            @Nonnull PopulationDomainBucket bucket
    ) {
        return findRows(profileId, bucket, COMMITTED_PHASES);
    }

    @Nonnull
    List<PopulationDomainReservation> findPendingByProfileAndBucket(
            @Nonnull ProfileId profileId,
            @Nonnull PopulationDomainBucket bucket
    ) {
        return findRows(profileId, bucket, "NOT IN ('DURABLE', 'PUBLISHED')");
    }

    @Nonnull
    List<PopulationDomainReservation> findPendingByProfile(
            @Nonnull ProfileId profileId
    ) {
        return findRows(profileId, null, "NOT IN ('DURABLE', 'PUBLISHED')");
    }

    boolean convergeExact(@Nonnull PopulationDomainConvergencePlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Domain convergence plan is required");
        }
        if (!validateSourceRows(plan)) {
            return false;
        }
        try {
            for (PopulationDomainConvergencePlan.SourceRow row : plan.sourceRows()) {
                if (!row.changesDeltas()) {
                    continue;
                }
                int changed = row.residualOwnedDelta() == 0
                        && row.residualDeployableDelta() == 0
                        ? deleteExact(row.expected())
                        : updateExact(row);
                if (changed != 1) {
                    return false;
                }
            }
            return true;
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_domain_converge_exact", failure);
        }
    }

    static PopulationDomainReservation read(ResultSet row) throws SQLException {
        PopulationDomainScope scope = PopulationDomainScope.valueOf(
                row.getString("scope_kind")
        );
        PopulationDomainBucket bucket = new PopulationDomainBucket(
                OwnerId.parse(row.getString("owner_uuid")),
                row.getString("domain_id"),
                scope,
                scope == PopulationDomainScope.GLOBAL
                        ? null
                        : row.getString("owner_world_key")
        );
        long revision = row.getLong("expected_lifecycle_revision");
        return new PopulationDomainReservation(
                OperationId.parse(row.getString("operation_id")),
                ProfileId.parse(row.getString("profile_id")),
                row.wasNull() ? null : new LifecycleRevision(revision),
                bucket,
                row.getInt("owned_delta"),
                row.getInt("deployable_delta"),
                row.getInt("weight"),
                row.getInt("snapshotted_max_owned"),
                row.getInt("snapshotted_max_deployable"),
                0,
                0,
                row.getLong("policy_revision"),
                row.getLong("created_at_ms")
        );
    }

    private boolean validateSourceRows(PopulationDomainConvergencePlan plan) {
        List<PopulationDomainReservation> actual =
                findCommittedByProfile(plan.profileId());
        List<PopulationDomainReservation> expected = plan.sourceRows().stream()
                .map(PopulationDomainConvergencePlan.SourceRow::expected)
                .sorted(Comparator.comparing(
                                (PopulationDomainReservation row) -> row.operationId().toString()
                        )
                        .thenComparing(PopulationDomainReservation::bucket))
                .toList();
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!samePersisted(actual.get(index), expected.get(index))) {
                return false;
            }
        }
        for (PopulationDomainBucket bucket : plan.sourceBuckets()) {
            if (!findPendingByProfileAndBucket(plan.profileId(), bucket).isEmpty()) {
                return false;
            }
        }
        return plan.sourceRows().isEmpty()
                ? findPendingByProfile(plan.profileId()).isEmpty()
                : true;
    }

    private int deleteExact(PopulationDomainReservation expected)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM population_domain_reservation
                WHERE operation_id = ? AND profile_id = ?
                  AND expected_lifecycle_revision IS ?
                  AND owner_uuid = ? AND domain_id = ? AND scope_kind = ?
                  AND owner_world_key = ? AND owned_delta = ?
                  AND deployable_delta = ? AND weight = ?
                  AND snapshotted_max_owned = ?
                  AND snapshotted_max_deployable = ?
                  AND policy_revision = ? AND created_at_ms = ?
                """)) {
            bindFullIdentity(statement, expected, 1);
            return statement.executeUpdate();
        }
    }

    private int updateExact(PopulationDomainConvergencePlan.SourceRow row)
            throws SQLException {
        PopulationDomainReservation expected = row.expected();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE population_domain_reservation
                SET owned_delta = ?, deployable_delta = ?
                WHERE operation_id = ? AND profile_id = ?
                  AND expected_lifecycle_revision IS ?
                  AND owner_uuid = ? AND domain_id = ? AND scope_kind = ?
                  AND owner_world_key = ? AND owned_delta = ?
                  AND deployable_delta = ? AND weight = ?
                  AND snapshotted_max_owned = ?
                  AND snapshotted_max_deployable = ?
                  AND policy_revision = ? AND created_at_ms = ?
                """)) {
            statement.setInt(1, row.residualOwnedDelta());
            statement.setInt(2, row.residualDeployableDelta());
            bindFullIdentity(statement, expected, 3);
            return statement.executeUpdate();
        }
    }

    private void bindFullIdentity(
            PreparedStatement statement,
            PopulationDomainReservation reservation,
            int start
    ) throws SQLException {
        statement.setString(start, reservation.operationId().toString());
        statement.setString(start + 1, reservation.profileId().toString());
        if (reservation.expectedLifecycleRevision() == null) {
            statement.setNull(start + 2, Types.BIGINT);
        } else {
            statement.setLong(
                    start + 2,
                    reservation.expectedLifecycleRevision().value()
            );
        }
        statement.setString(start + 3, reservation.bucket().ownerId().toString());
        statement.setString(start + 4, reservation.bucket().domainId());
        statement.setString(start + 5, reservation.bucket().scope().name());
        statement.setString(start + 6, reservation.bucket().storedWorldKey());
        statement.setInt(start + 7, reservation.ownedDelta());
        statement.setInt(start + 8, reservation.deployableDelta());
        statement.setInt(start + 9, reservation.weight());
        statement.setInt(start + 10, reservation.snapshottedMaxOwned());
        statement.setInt(start + 11, reservation.snapshottedMaxDeployable());
        statement.setLong(start + 12, reservation.policyRevision());
        statement.setLong(start + 13, reservation.createdAtMs());
    }

    private List<PopulationDomainReservation> findRows(
            ProfileId profile,
            PopulationDomainBucket bucket,
            String phasePredicate
    ) {
        if (profile == null || phasePredicate == null) {
            throw new IllegalArgumentException("Domain convergence query is incomplete");
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(COLUMNS)
                .append(" FROM population_domain_reservation reservation")
                .append(" JOIN operation_envelope envelope")
                .append(" ON envelope.operation_id = reservation.operation_id")
                .append(" WHERE reservation.profile_id = ?")
                .append(" AND envelope.phase ")
                .append(phasePredicate);
        if (bucket != null) {
            sql.append(" AND reservation.owner_uuid = ?")
                    .append(" AND reservation.domain_id = ?")
                    .append(" AND reservation.scope_kind = ?")
                    .append(" AND reservation.owner_world_key = ?");
        }
        sql.append(" ORDER BY reservation.operation_id, reservation.owner_uuid,")
                .append(" reservation.domain_id, reservation.scope_kind,")
                .append(" reservation.owner_world_key");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, profile.toString());
            if (bucket != null) {
                statement.setString(2, bucket.ownerId().toString());
                statement.setString(3, bucket.domainId());
                statement.setString(4, bucket.scope().name());
                statement.setString(5, bucket.storedWorldKey());
            }
            ArrayList<PopulationDomainReservation> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_domain_convergence_find", failure);
        }
    }

    private boolean samePersisted(
            PopulationDomainReservation actual,
            PopulationDomainReservation expected
    ) {
        return actual.operationId().equals(expected.operationId())
                && actual.profileId().equals(expected.profileId())
                && Objects.equals(
                actual.expectedLifecycleRevision(),
                expected.expectedLifecycleRevision()
        )
                && actual.bucket().equals(expected.bucket())
                && actual.ownedDelta() == expected.ownedDelta()
                && actual.deployableDelta() == expected.deployableDelta()
                && actual.weight() == expected.weight()
                && actual.snapshottedMaxOwned() == expected.snapshottedMaxOwned()
                && actual.snapshottedMaxDeployable()
                == expected.snapshottedMaxDeployable()
                && actual.policyRevision() == expected.policyRevision()
                && actual.createdAtMs() == expected.createdAtMs();
    }

    private PersistenceStoreException storeFailure(String operation, Throwable failure) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }
}

package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.OwnerPopulationEvidencePort;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceAssessment;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceBatch;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceObservation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Connection-bound durable evidence store for owner-population reconciliation.
 */
public final class SqliteOwnerPopulationEvidenceStore
        implements OwnerPopulationEvidencePort {
    private static final String BATCH_COLUMNS = """
            boot_id, world_key, reconciliation_generation, source_kind,
            status, opened_at_ms, closed_at_ms, failure_code
            """;
    private static final String OBSERVATION_COLUMNS = """
            boot_id, world_key, reconciliation_generation, source_kind,
            profile_id, owner_observed, owner_uuid, owner_world_key,
            observed_at_ms
            """;

    private final Connection connection;

    public SqliteOwnerPopulationEvidenceStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Population evidence connection is required"
            );
        }
        this.connection = connection;
    }

    @Override
    public Optional<PopulationEvidenceBatch> findBatch(
            PopulationEvidenceBatch.Key key
    ) {
        require(key, "Evidence batch key");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + BATCH_COLUMNS + """
                         FROM population_evidence_batch
                         WHERE boot_id = ? AND world_key = ?
                           AND reconciliation_generation = ?
                           AND source_kind = ?
                        """)) {
            bindKey(statement, key, 1);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(readBatch(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_evidence_find_batch", failure);
        }
    }

    @Override
    public Optional<PopulationEvidenceObservation> findObservation(
            PopulationEvidenceBatch.Key key,
            ProfileId profileId
    ) {
        require(key, "Evidence batch key");
        require(profileId, "Evidence profile");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + OBSERVATION_COLUMNS + """
                         FROM population_evidence_observation
                         WHERE boot_id = ? AND world_key = ?
                           AND reconciliation_generation = ?
                           AND source_kind = ? AND profile_id = ?
                        """)) {
            bindKey(statement, key, 1);
            statement.setString(5, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(readObservation(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "population_evidence_find_observation",
                    failure
            );
        }
    }

    @Override
    public PersistenceMutationResult<PopulationEvidenceBatch> open(
            PopulationEvidenceBatch batch
    ) {
        require(batch, "Evidence batch");
        if (batch.status() != PopulationEvidenceBatch.Status.OPEN) {
            throw new IllegalArgumentException(
                    "Only open evidence batches can be created"
            );
        }
        PopulationEvidenceBatch existing =
                findBatch(batch.key()).orElse(null);
        if (existing != null) {
            return existing.equals(batch)
                    ? PersistenceMutationResult.applied(existing)
                    : rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO population_evidence_batch(
                    boot_id, world_key, reconciliation_generation, source_kind,
                    status, opened_at_ms, closed_at_ms, failure_code
                ) VALUES (?, ?, ?, ?, 'OPEN', ?, NULL, NULL)
                """)) {
            bindKey(statement, batch.key(), 1);
            statement.setLong(5, batch.openedAtMs());
            statement.executeUpdate();
            return PersistenceMutationResult.applied(batch);
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return rejected(PersistenceMutationStatus.CONFLICT);
            }
            throw storeFailure("population_evidence_open_batch", failure);
        }
    }

    @Override
    public PersistenceMutationResult<PopulationEvidenceObservation> observe(
            PopulationEvidenceObservation observation
    ) {
        require(observation, "Population evidence observation");
        PopulationEvidenceObservation existing = findObservation(
                observation.batchKey(), observation.profileId()
        ).orElse(null);
        if (existing != null) {
            return existing.equals(observation)
                    ? PersistenceMutationResult.applied(existing)
                    : rejected(PersistenceMutationStatus.CONFLICT);
        }
        PopulationEvidenceBatch batch =
                findBatch(observation.batchKey()).orElse(null);
        if (batch == null) {
            return rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (batch.status() != PopulationEvidenceBatch.Status.OPEN) {
            return rejected(PersistenceMutationStatus.PHASE_MISMATCH);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO population_evidence_observation(
                    boot_id, world_key, reconciliation_generation, source_kind,
                    profile_id, owner_observed, owner_uuid, owner_world_key,
                    observed_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindKey(statement, observation.batchKey(), 1);
            statement.setString(5, observation.profileId().toString());
            statement.setInt(6, observation.ownerObserved() ? 1 : 0);
            setNullableText(statement, 7, text(observation.ownerId()));
            setNullableText(statement, 8, observation.ownerWorldKey());
            statement.setLong(9, observation.observedAtMs());
            statement.executeUpdate();
            return PersistenceMutationResult.applied(observation);
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return rejected(PersistenceMutationStatus.CONFLICT);
            }
            throw storeFailure(
                    "population_evidence_observe_profile",
                    failure
            );
        }
    }

    @Override
    public PersistenceMutationResult<PopulationEvidenceBatch> close(
            PopulationEvidenceBatch.Key key,
            PopulationEvidenceBatch.Status result,
            long closedAtMs,
            String failureCode
    ) {
        require(key, "Evidence batch key");
        if (result != PopulationEvidenceBatch.Status.SEALED
                && result != PopulationEvidenceBatch.Status.FAILED) {
            throw new IllegalArgumentException(
                    "Evidence close result must be sealed or failed"
            );
        }
        PopulationEvidenceBatch current = findBatch(key).orElse(null);
        if (current == null) {
            return rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        PopulationEvidenceBatch next = new PopulationEvidenceBatch(
                key,
                result,
                current.openedAtMs(),
                closedAtMs,
                failureCode
        );
        if (current.status() != PopulationEvidenceBatch.Status.OPEN) {
            return current.equals(next)
                    ? PersistenceMutationResult.applied(current)
                    : rejected(PersistenceMutationStatus.PHASE_MISMATCH);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE population_evidence_batch
                SET status = ?, closed_at_ms = ?, failure_code = ?
                WHERE boot_id = ? AND world_key = ?
                  AND reconciliation_generation = ?
                  AND source_kind = ? AND status = 'OPEN'
                """)) {
            statement.setString(1, result.name());
            statement.setLong(2, closedAtMs);
            setNullableText(statement, 3, next.failureCode());
            bindKey(statement, key, 4);
            if (statement.executeUpdate() != 1) {
                return rejected(PersistenceMutationStatus.PHASE_MISMATCH);
            }
            return PersistenceMutationResult.applied(next);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_evidence_close_batch", failure);
        }
    }

    @Override
    public PopulationEvidenceAssessment assessPositive(
            PopulationEvidenceBatch.Key key,
            ProfileId profileId,
            OwnerId expectedOwnerId,
            String expectedOwnerWorldKey
    ) {
        require(key, "Evidence batch key");
        require(profileId, "Evidence profile");
        String expectedWorld = normalize(expectedOwnerWorldKey);
        if (expectedOwnerId == null && expectedWorld != null) {
            throw new IllegalArgumentException(
                    "Expected owner world requires an owner"
            );
        }
        PopulationEvidenceObservation observation =
                findObservation(key, profileId).orElse(null);
        if (observation == null) {
            return assessment(
                    PopulationEvidenceAssessment.Status.INCOMPLETE,
                    "population_positive_observation_missing",
                    null
            );
        }
        if (!observation.ownerObserved()
                || (observation.ownerId() != null
                && observation.ownerWorldKey() == null)
                || (expectedOwnerId != null && expectedWorld == null)) {
            return assessment(
                    PopulationEvidenceAssessment.Status.PRESENT_INCOMPLETE,
                    "population_positive_owner_evidence_incomplete",
                    observation
            );
        }
        boolean matches = Objects.equals(
                observation.ownerId(), expectedOwnerId
        ) && Objects.equals(observation.ownerWorldKey(), expectedWorld);
        return assessment(
                matches
                        ? PopulationEvidenceAssessment.Status.PRESENT_MATCH
                        : PopulationEvidenceAssessment.Status.PRESENT_CONTRADICTION,
                matches
                        ? "population_positive_evidence_matches"
                        : "population_positive_owner_contradiction",
                observation
        );
    }

    @Override
    public PopulationEvidenceAssessment assessAbsence(
            String bootId,
            String worldKey,
            ReconciliationGeneration generation,
            ProfileId profileId
    ) {
        String boot = requireText(bootId, "Evidence boot ID");
        String world = requireText(worldKey, "Evidence world key");
        require(generation, "Evidence generation");
        require(profileId, "Evidence profile");
        PopulationEvidenceBatch.Key disk = new PopulationEvidenceBatch.Key(
                boot,
                world,
                generation,
                PopulationEvidenceBatch.Source.DISK
        );
        PopulationEvidenceBatch.Key live = new PopulationEvidenceBatch.Key(
                boot,
                world,
                generation,
                PopulationEvidenceBatch.Source.LIVE
        );
        PopulationEvidenceObservation present =
                findObservation(disk, profileId)
                        .or(() -> findObservation(live, profileId))
                        .orElse(null);
        if (present != null) {
            return assessment(
                    PopulationEvidenceAssessment.Status.PRESENT_INCOMPLETE,
                    "population_absence_contradicted_by_presence",
                    present
            );
        }
        if (!sealed(disk) || !sealed(live)) {
            return assessment(
                    PopulationEvidenceAssessment.Status.INCOMPLETE,
                    "population_absence_sources_incomplete",
                    null
            );
        }
        return assessment(
                PopulationEvidenceAssessment.Status.ABSENT_PROVEN,
                "population_absence_proven",
                null
        );
    }

    private boolean sealed(PopulationEvidenceBatch.Key key) {
        return findBatch(key)
                .map(batch -> batch.status()
                        == PopulationEvidenceBatch.Status.SEALED)
                .orElse(false);
    }

    private PopulationEvidenceBatch readBatch(ResultSet row)
            throws SQLException {
        return new PopulationEvidenceBatch(
                readKey(row),
                PopulationEvidenceBatch.Status.valueOf(
                        row.getString("status")
                ),
                row.getLong("opened_at_ms"),
                nullableLong(row, "closed_at_ms"),
                row.getString("failure_code")
        );
    }

    private PopulationEvidenceObservation readObservation(ResultSet row)
            throws SQLException {
        String owner = row.getString("owner_uuid");
        return new PopulationEvidenceObservation(
                readKey(row),
                ProfileId.parse(row.getString("profile_id")),
                row.getInt("owner_observed") == 1,
                owner == null ? null : OwnerId.parse(owner),
                row.getString("owner_world_key"),
                row.getLong("observed_at_ms")
        );
    }

    private PopulationEvidenceBatch.Key readKey(ResultSet row)
            throws SQLException {
        return new PopulationEvidenceBatch.Key(
                row.getString("boot_id"),
                row.getString("world_key"),
                new ReconciliationGeneration(
                        row.getLong("reconciliation_generation")
                ),
                PopulationEvidenceBatch.Source.valueOf(
                        row.getString("source_kind")
                )
        );
    }

    private void bindKey(
            PreparedStatement statement,
            PopulationEvidenceBatch.Key key,
            int start
    ) throws SQLException {
        statement.setString(start, key.bootId());
        statement.setString(start + 1, key.worldKey());
        statement.setLong(start + 2, key.generation().value());
        statement.setString(start + 3, key.source().name());
    }

    private void setNullableText(
            PreparedStatement statement,
            int index,
            @Nullable String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private Long nullableLong(ResultSet row, String column)
            throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private PopulationEvidenceAssessment assessment(
            PopulationEvidenceAssessment.Status status,
            String reason,
            PopulationEvidenceObservation observation
    ) {
        return new PopulationEvidenceAssessment(status, reason, observation);
    }

    private <T> PersistenceMutationResult<T> rejected(
            PersistenceMutationStatus status
    ) {
        return PersistenceMutationResult.rejected(status);
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

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}


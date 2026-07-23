package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecyclePort;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationId;
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
 * Connection-bound SQLite adapter for the single replacement lifecycle authority.
 *
 * <p>All state, owner, location, fence, reconciliation, and quarantine changes share one
 * compare-and-transition statement and therefore one revision path.</p>
 */
public final class SqliteCompanionLifecycleStore implements CompanionLifecyclePort {
    private static final String SELECT_COLUMNS = """
            profile_id, owner_uuid, lifecycle_state, location_kind, location_key,
            world_key, revision, active_operation_id, state_changed_at_ms,
            last_reconciled_generation, quarantine_incident_id
            """;

    private final Connection connection;

    public SqliteCompanionLifecycleStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Lifecycle store connection is required");
        }
        this.connection = connection;
    }

    @Override
    public Optional<CompanionLifecycle> findByProfile(ProfileId profileId) {
        require(profileId, "Profile ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM companion_lifecycle WHERE profile_id = ?")) {
            statement.setString(1, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readLifecycle(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("lifecycle_find_profile", failure);
        }
    }

    @Override
    public List<CompanionLifecycle> findByOwner(OwnerId ownerId) {
        require(ownerId, "Owner ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS
                        + " FROM companion_lifecycle WHERE owner_uuid = ? ORDER BY profile_id")) {
            statement.setString(1, ownerId.toString());
            return readRows(statement);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("lifecycle_find_owner", failure);
        }
    }

    @Override
    public List<CompanionLifecycle> findByLocation(LifecycleLocation location) {
        require(location, "Lifecycle location");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + """
                         FROM companion_lifecycle
                         WHERE location_kind = ?
                           AND location_key IS ?
                           AND world_key IS ?
                         ORDER BY profile_id
                        """)) {
            statement.setString(1, location.kind().name());
            setNullableText(statement, 2, location.key());
            setNullableText(statement, 3, location.worldKey());
            return readRows(statement);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("lifecycle_find_location", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionLifecycle> create(CompanionLifecycle initial) {
        require(initial, "Initial lifecycle");
        if (!initial.revision().equals(LifecycleRevision.INITIAL)
                || initial.activeOperationId() != null) {
            throw new IllegalArgumentException(
                    "New lifecycles must begin at revision zero without an operation fence"
            );
        }
        Optional<CompanionLifecycle> existing = findByProfile(initial.profileId());
        if (existing.isPresent()) {
            return existing.get().equals(initial)
                    ? PersistenceMutationResult.applied(existing.get())
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_lifecycle(
                    profile_id, owner_uuid, lifecycle_state, location_kind,
                    location_key, world_key, revision, active_operation_id,
                    state_changed_at_ms, last_reconciled_generation, quarantine_incident_id
                ) VALUES (?, ?, ?, ?, ?, ?, 0, NULL, ?, ?, ?)
                """)) {
            bindIdentityAndLocation(statement, initial, 1);
            statement.setLong(7, initial.stateChangedAtMs());
            statement.setLong(8, initial.lastReconciledGeneration().value());
            setNullableText(statement, 9, text(initial.quarantineIncidentId()));
            statement.executeUpdate();
            return PersistenceMutationResult.applied(initial);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("lifecycle_create", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionLifecycle> transition(
            LifecycleTransition transition
    ) {
        require(transition, "Lifecycle transition");
        CompanionLifecycle current = findByProfile(transition.next().profileId()).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (!current.revision().equals(transition.expectedRevision())) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        if (!java.util.Objects.equals(
                current.activeOperationId(), transition.expectedOperationId())) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        if (!newOperationFenceIsValid(current, transition.next())) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_lifecycle
                SET owner_uuid = ?, lifecycle_state = ?, location_kind = ?,
                    location_key = ?, world_key = ?, revision = ?, active_operation_id = ?,
                    state_changed_at_ms = ?, last_reconciled_generation = ?,
                    quarantine_incident_id = ?
                WHERE profile_id = ? AND revision = ?
                  AND ((active_operation_id = ?)
                       OR (active_operation_id IS NULL AND ? IS NULL))
                """)) {
            bindTransition(statement, transition);
            if (statement.executeUpdate() != 1) {
                return classifyFailedTransition(transition);
            }
            return PersistenceMutationResult.applied(transition.next());
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("lifecycle_transition", failure);
        }
    }

    private boolean newOperationFenceIsValid(CompanionLifecycle current,
                                             CompanionLifecycle next) {
        OperationId nextOperation = next.activeOperationId();
        if (nextOperation == null || nextOperation.equals(current.activeOperationId())) {
            return true;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM operation_envelope envelope
                JOIN operation_participant participant
                  ON participant.operation_id = envelope.operation_id
                WHERE envelope.operation_id = ?
                  AND envelope.expected_lifecycle_revision = ?
                  AND participant.scope_type = 'PROFILE'
                  AND participant.scope_key = ?
                """)) {
            statement.setString(1, nextOperation.toString());
            statement.setLong(2, current.revision().value());
            statement.setString(3, current.profileId().toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("lifecycle_validate_operation_fence", failure);
        }
    }

    private PersistenceMutationResult<CompanionLifecycle> classifyFailedTransition(
            LifecycleTransition transition
    ) {
        CompanionLifecycle current = findByProfile(transition.next().profileId()).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (!current.revision().equals(transition.expectedRevision())) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
    }

    private void bindIdentityAndLocation(PreparedStatement statement,
                                         CompanionLifecycle lifecycle,
                                         int start) throws SQLException {
        statement.setString(start, lifecycle.profileId().toString());
        setNullableText(statement, start + 1, text(lifecycle.ownerId()));
        statement.setString(start + 2, lifecycle.state().name());
        statement.setString(start + 3, lifecycle.location().kind().name());
        setNullableText(statement, start + 4, lifecycle.location().key());
        setNullableText(statement, start + 5, lifecycle.location().worldKey());
    }

    private void bindTransition(PreparedStatement statement,
                                LifecycleTransition transition) throws SQLException {
        CompanionLifecycle next = transition.next();
        setNullableText(statement, 1, text(next.ownerId()));
        statement.setString(2, next.state().name());
        statement.setString(3, next.location().kind().name());
        setNullableText(statement, 4, next.location().key());
        setNullableText(statement, 5, next.location().worldKey());
        statement.setLong(6, next.revision().value());
        setNullableText(statement, 7, text(next.activeOperationId()));
        statement.setLong(8, next.stateChangedAtMs());
        statement.setLong(9, next.lastReconciledGeneration().value());
        setNullableText(statement, 10, text(next.quarantineIncidentId()));
        statement.setString(11, next.profileId().toString());
        statement.setLong(12, transition.expectedRevision().value());
        setNullableText(statement, 13, text(transition.expectedOperationId()));
        setNullableText(statement, 14, text(transition.expectedOperationId()));
    }

    private List<CompanionLifecycle> readRows(PreparedStatement statement) throws SQLException {
        ArrayList<CompanionLifecycle> rows = new ArrayList<>();
        try (ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                rows.add(readLifecycle(row));
            }
        }
        return List.copyOf(rows);
    }

    private CompanionLifecycle readLifecycle(ResultSet row) throws SQLException {
        String owner = row.getString("owner_uuid");
        String operation = row.getString("active_operation_id");
        String incident = row.getString("quarantine_incident_id");
        LifecycleLocation location = new LifecycleLocation(
                LifecycleLocationKind.valueOf(row.getString("location_kind")),
                row.getString("location_key"),
                row.getString("world_key")
        );
        return new CompanionLifecycle(
                ProfileId.parse(row.getString("profile_id")),
                owner == null ? null : OwnerId.parse(owner),
                LifecycleState.valueOf(row.getString("lifecycle_state")),
                location,
                new LifecycleRevision(row.getLong("revision")),
                operation == null ? null : OperationId.parse(operation),
                row.getLong("state_changed_at_ms"),
                new OperationGeneration(row.getLong("last_reconciled_generation")),
                incident == null ? null : IncidentId.parse(incident)
        );
    }

    private void setNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private PersistenceStoreException storeFailure(String operation, Throwable failure) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}

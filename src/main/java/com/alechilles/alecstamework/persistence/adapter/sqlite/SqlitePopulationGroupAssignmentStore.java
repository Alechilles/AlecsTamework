package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Focused normalized classification and complete-membership SQLite store. */
final class SqlitePopulationGroupAssignmentStore {
    private static final String COLUMNS = """
            profile_id, role_id, policy_revision, source_metadata_revision,
            source_lifecycle_revision, assignment_revision, assigned_at_ms
            """;

    private final Connection connection;

    SqlitePopulationGroupAssignmentStore(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Population group assignment connection is required"
            );
        }
        this.connection = connection;
    }

    Optional<PopulationGroupAssignment> find(ProfileId profileId) {
        require(profileId, "Population group profile");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + """
                         FROM population_group_classification
                         WHERE profile_id = ?
                        """)) {
            statement.setString(1, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(read(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_group_assignment_find", failure);
        }
    }

    List<PopulationGroupAssignment> findAll() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + """
                         FROM population_group_classification
                         ORDER BY profile_id
                        """);
             ResultSet rows = statement.executeQuery()) {
            ArrayList<PopulationGroupAssignment> assignments =
                    new ArrayList<>();
            while (rows.next()) {
                assignments.add(read(rows));
            }
            return List.copyOf(assignments);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "population_group_assignment_find_all",
                    failure
            );
        }
    }

    List<ProfileId> findStaleProfiles() {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT classification.profile_id
                FROM population_group_classification classification
                LEFT JOIN companion_profile profile
                  ON profile.profile_id = classification.profile_id
                LEFT JOIN companion_lifecycle lifecycle
                  ON lifecycle.profile_id = classification.profile_id
                WHERE profile.profile_id IS NULL
                   OR lifecycle.profile_id IS NULL
                   OR NOT (classification.role_id IS profile.role_id)
                   OR classification.source_metadata_revision
                        <> profile.metadata_revision
                   OR classification.source_lifecycle_revision
                        > lifecycle.revision
                   OR EXISTS(
                       SELECT 1
                       FROM population_group_membership membership
                         WHERE membership.profile_id = classification.profile_id
                           AND membership.scope_kind = 'PER_WORLD'
                           AND lifecycle.owner_uuid IS NOT NULL
                           AND lifecycle.owner_world_key IS NULL
                   )
                ORDER BY classification.profile_id
                """);
             ResultSet rows = statement.executeQuery()) {
            ArrayList<ProfileId> stale = new ArrayList<>();
            while (rows.next()) {
                stale.add(ProfileId.parse(rows.getString(1)));
            }
            return List.copyOf(stale);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("population_group_assignment_stale", failure);
        }
    }

    PersistenceMutationResult<PopulationGroupAssignment> replace(
            Long expectedRevision,
            PopulationGroupAssignment next
    ) {
        require(next, "Population group assignment");
        PopulationGroupAssignment current =
                find(next.profileId()).orElse(null);
        if (current != null && current.equals(next)) {
            return PersistenceMutationResult.applied(current);
        }
        if (!sourceMatches(next)) {
            return rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        if (!revisionMatches(current, expectedRevision, next)) {
            return rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        try {
            upsert(next);
            replaceMemberships(next);
            return PersistenceMutationResult.applied(next);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "population_group_assignment_replace",
                    failure
            );
        }
    }

    private boolean sourceMatches(PopulationGroupAssignment next) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile.role_id, profile.metadata_revision,
                       lifecycle.revision
                FROM companion_profile profile
                JOIN companion_lifecycle lifecycle
                  ON lifecycle.profile_id = profile.profile_id
                WHERE profile.profile_id = ?
                """)) {
            statement.setString(1, next.profileId().toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        && java.util.Objects.equals(
                        row.getString("role_id"), next.roleId()
                )
                        && row.getLong("metadata_revision")
                        == next.sourceMetadataRevision()
                        && row.getLong("revision")
                        == next.sourceLifecycleRevision().value();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "population_group_assignment_source",
                    failure
            );
        }
    }

    private boolean revisionMatches(
            PopulationGroupAssignment current,
            Long expected,
            PopulationGroupAssignment next
    ) {
        if (current == null) {
            return expected == null && next.assignmentRevision() == 1;
        }
        return expected != null
                && expected == current.assignmentRevision()
                && current.assignmentRevision() != Long.MAX_VALUE
                && next.assignmentRevision()
                == current.assignmentRevision() + 1;
    }

    private void upsert(PopulationGroupAssignment next)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO population_group_classification(
                    profile_id, role_id, policy_revision,
                    source_metadata_revision, source_lifecycle_revision,
                    assignment_revision, assigned_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id) DO UPDATE SET
                    role_id = excluded.role_id,
                    policy_revision = excluded.policy_revision,
                    source_metadata_revision =
                        excluded.source_metadata_revision,
                    source_lifecycle_revision =
                        excluded.source_lifecycle_revision,
                    assignment_revision = excluded.assignment_revision,
                    assigned_at_ms = excluded.assigned_at_ms
                """)) {
            statement.setString(1, next.profileId().toString());
            setNullableText(statement, 2, next.roleId());
            statement.setLong(3, next.policyRevision());
            statement.setLong(4, next.sourceMetadataRevision());
            statement.setLong(
                    5, next.sourceLifecycleRevision().value()
            );
            statement.setLong(6, next.assignmentRevision());
            statement.setLong(7, next.assignedAtMs());
            statement.executeUpdate();
        }
    }

    private void replaceMemberships(PopulationGroupAssignment next)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM population_group_membership
                WHERE profile_id = ?
                """)) {
            delete.setString(1, next.profileId().toString());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO population_group_membership(
                    profile_id, group_id, scope_kind
                ) VALUES (?, ?, ?)
                """)) {
            for (PopulationGroupMembership membership
                    : next.memberships()) {
                insert.setString(1, next.profileId().toString());
                insert.setString(2, membership.groupId());
                insert.setString(3, membership.scope().name());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private PopulationGroupAssignment read(ResultSet row)
            throws SQLException {
        ProfileId profileId = ProfileId.parse(
                row.getString("profile_id")
        );
        return new PopulationGroupAssignment(
                profileId,
                row.getString("role_id"),
                memberships(profileId),
                row.getLong("policy_revision"),
                row.getLong("source_metadata_revision"),
                new LifecycleRevision(
                        row.getLong("source_lifecycle_revision")
                ),
                row.getLong("assignment_revision"),
                row.getLong("assigned_at_ms")
        );
    }

    private List<PopulationGroupMembership> memberships(
            ProfileId profileId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT group_id, scope_kind
                FROM population_group_membership
                WHERE profile_id = ?
                ORDER BY group_id
                """)) {
            statement.setString(1, profileId.toString());
            ArrayList<PopulationGroupMembership> memberships =
                    new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    memberships.add(new PopulationGroupMembership(
                            rows.getString("group_id"),
                            PopulationGroupScope.valueOf(
                                    rows.getString("scope_kind")
                            )
                    ));
                }
            }
            return List.copyOf(memberships);
        }
    }

    private void setNullableText(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private <T> PersistenceMutationResult<T> rejected(
            PersistenceMutationStatus status
    ) {
        return PersistenceMutationResult.rejected(status);
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


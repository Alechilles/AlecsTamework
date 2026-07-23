package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.CommandRosterMutationOutcome;
import com.alechilles.alecstamework.companion.command.CommandRosterPort;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/** Normalized command-family and one-slot-per-profile SQLite authority. */
public final class SqliteCommandRosterStore
        implements CommandRosterPort {
    private final Connection connection;
    private final SqliteCommandRosterQueryStore queries;

    public SqliteCommandRosterStore(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Command roster connection is required"
            );
        }
        this.connection = connection;
        this.queries = new SqliteCommandRosterQueryStore(connection);
    }

    @Override
    public Optional<CommandRoster> findRoster(
            CommandFamilyKey familyKey
    ) {
        require(familyKey, "Command family");
        return queries.findRoster(familyKey);
    }

    @Override
    public Optional<CommandRosterMembership> findByProfile(
            ProfileId profileId
    ) {
        require(profileId, "Command roster profile");
        return queries.findByProfile(profileId);
    }

    @Override
    public Optional<CommandRosterMembership> findBySlot(
            CommandRosterSlotId slotId
    ) {
        require(slotId, "Command roster slot");
        return queries.findBySlot(slotId);
    }

    @Override
    public List<CommandRoster> findAllRosters() {
        return queries.findAllRosters();
    }

    @Override
    public PersistenceMutationResult<CommandRosterMutationOutcome> upsert(
            long expectedRosterRevision,
            Long expectedMembershipRevision,
            CommandRosterMembershipDraft target
    ) {
        require(target, "Command roster target");
        if (expectedRosterRevision < 0
                || expectedMembershipRevision != null
                && expectedMembershipRevision <= 0) {
            throw new IllegalArgumentException(
                    "Valid expected roster revisions are required"
            );
        }
        CommandRosterMembership current =
                findByProfile(target.profileId()).orElse(null);
        if (!membershipRevisionMatches(
                current, expectedMembershipRevision
        )) {
            return rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        if (current != null && (!current.familyKey().equals(
                target.familyKey()
        ) || !current.slotId().equals(target.slotId()))) {
            return rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        CommandRosterMembership slot =
                findBySlot(target.slotId()).orElse(null);
        if (slot != null && !slot.profileId().equals(
                target.profileId()
        )) {
            return rejected(PersistenceMutationStatus.CONFLICT);
        }
        CommandRoster roster =
                findRoster(target.familyKey()).orElse(null);
        long currentRosterRevision =
                roster == null ? 0 : roster.rosterRevision();
        if (currentRosterRevision != expectedRosterRevision) {
            return rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        if (!canonicalOwnerMatches(target)) {
            return rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        if (current != null && samePreferences(current, target)) {
            return PersistenceMutationResult.applied(
                    new CommandRosterMutationOutcome(
                            target.familyKey(),
                            currentRosterRevision,
                            currentRosterRevision,
                            current,
                            current
                    )
            );
        }
        try {
            ensureFamily(target.familyKey(), target.changedAtMs());
            CommandRosterMembership next = membership(current, target);
            write(next);
            long nextRosterRevision =
                    Math.addExact(currentRosterRevision, 1);
            updateFamily(
                    target.familyKey(),
                    nextRosterRevision,
                    target.changedAtMs()
            );
            return PersistenceMutationResult.applied(
                    new CommandRosterMutationOutcome(
                            target.familyKey(),
                            currentRosterRevision,
                            nextRosterRevision,
                            current,
                            next
                    )
            );
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("command_roster_upsert", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CommandRosterMutationOutcome> remove(
            long expectedRosterRevision,
            long expectedMembershipRevision,
            CommandFamilyKey familyKey,
            ProfileId profileId,
            long changedAtMs
    ) {
        require(familyKey, "Command family");
        require(profileId, "Command roster profile");
        if (expectedRosterRevision < 0
                || expectedMembershipRevision <= 0) {
            throw new IllegalArgumentException(
                    "Valid expected roster revisions are required"
            );
        }
        CommandRosterMembership current =
                findByProfile(profileId).orElse(null);
        if (current == null) {
            return rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        CommandRoster roster = findRoster(familyKey).orElse(null);
        if (!familyKey.equals(current.familyKey())
                || current.membershipRevision()
                != expectedMembershipRevision) {
            return rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        if (roster == null || roster.rosterRevision()
                != expectedRosterRevision) {
            return rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        if (lifecycleOccupies(current)) {
            return rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM command_roster_membership
                WHERE profile_id = ? AND membership_revision = ?
                """)) {
            statement.setString(1, profileId.toString());
            statement.setLong(2, expectedMembershipRevision);
            if (statement.executeUpdate() != 1) {
                return rejected(PersistenceMutationStatus.REVISION_MISMATCH);
            }
            long nextRosterRevision =
                    Math.addExact(expectedRosterRevision, 1);
            updateFamily(familyKey, nextRosterRevision, changedAtMs);
            return PersistenceMutationResult.applied(
                    new CommandRosterMutationOutcome(
                            familyKey,
                            expectedRosterRevision,
                            nextRosterRevision,
                            current,
                            null
                    )
            );
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("command_roster_remove", failure);
        }
    }

    private boolean canonicalOwnerMatches(
            CommandRosterMembershipDraft target
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid
                FROM companion_lifecycle
                WHERE profile_id = ?
                """)) {
            statement.setString(1, target.profileId().toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && target.familyKey().ownerId()
                        .toString().equals(row.getString("owner_uuid"));
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "command_roster_owner_fence", failure
            );
        }
    }

    private boolean lifecycleOccupies(
            CommandRosterMembership current
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM companion_lifecycle
                WHERE profile_id = ?
                  AND lifecycle_state = 'ROSTER_STORED'
                """)) {
            statement.setString(1, current.profileId().toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "command_roster_lifecycle_fence", failure
            );
        }
    }

    private void ensureFamily(
            CommandFamilyKey familyKey,
            long changedAtMs
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO command_family(
                    owner_uuid, family_id, roster_revision,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, 0, ?, ?)
                """)) {
            bindFamily(statement, familyKey, 1);
            statement.setLong(3, changedAtMs);
            statement.setLong(4, changedAtMs);
            statement.executeUpdate();
        }
    }

    private void updateFamily(
            CommandFamilyKey familyKey,
            long nextRevision,
            long changedAtMs
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_family
                SET roster_revision = ?, updated_at_ms = ?
                WHERE owner_uuid = ? AND family_id = ?
                """)) {
            statement.setLong(1, nextRevision);
            statement.setLong(2, changedAtMs);
            bindFamily(statement, familyKey, 3);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "command_roster_family_update_missing"
                );
            }
        }
    }

    private void write(CommandRosterMembership membership)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO command_roster_membership(
                    slot_id, profile_id, owner_uuid, family_id,
                    membership_revision, group_id,
                    active_for_bulk_commands,
                    home_world_key, home_x, home_y, home_z,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id) DO UPDATE SET
                    membership_revision =
                        excluded.membership_revision,
                    group_id = excluded.group_id,
                    active_for_bulk_commands =
                        excluded.active_for_bulk_commands,
                    home_world_key = excluded.home_world_key,
                    home_x = excluded.home_x,
                    home_y = excluded.home_y,
                    home_z = excluded.home_z,
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            bindMembership(statement, membership);
            statement.executeUpdate();
        }
    }

    private CommandRosterMembership membership(
            CommandRosterMembership current,
            CommandRosterMembershipDraft target
    ) {
        return new CommandRosterMembership(
                target.slotId(),
                target.familyKey(),
                target.profileId(),
                current == null
                        ? 1
                        : Math.addExact(
                        current.membershipRevision(), 1
                ),
                target.groupId(),
                target.activeForBulkCommands(),
                target.home(),
                current == null
                        ? target.changedAtMs()
                        : current.createdAtMs(),
                target.changedAtMs()
        );
    }

    private void bindMembership(
            PreparedStatement statement,
            CommandRosterMembership membership
    ) throws SQLException {
        statement.setString(1, membership.slotId().toString());
        statement.setString(2, membership.profileId().toString());
        statement.setString(
                3, membership.familyKey().ownerId().toString()
        );
        statement.setString(4, membership.familyKey().familyId());
        statement.setLong(5, membership.membershipRevision());
        setText(statement, 6, membership.groupId());
        statement.setInt(
                7, membership.activeForBulkCommands() ? 1 : 0
        );
        CommandRosterHome home = membership.home();
        setText(statement, 8, home == null ? null : home.worldKey());
        setDouble(statement, 9, home == null ? null : home.x());
        setDouble(statement, 10, home == null ? null : home.y());
        setDouble(statement, 11, home == null ? null : home.z());
        statement.setLong(12, membership.createdAtMs());
        statement.setLong(13, membership.updatedAtMs());
    }

    private boolean membershipRevisionMatches(
            CommandRosterMembership current,
            Long expected
    ) {
        return current == null
                ? expected == null
                : expected != null
                && current.membershipRevision() == expected;
    }

    private boolean samePreferences(
            CommandRosterMembership current,
            CommandRosterMembershipDraft target
    ) {
        return java.util.Objects.equals(
                current.groupId(), target.groupId()
        )
                && current.activeForBulkCommands()
                == target.activeForBulkCommands()
                && java.util.Objects.equals(
                current.home(), target.home()
        );
    }

    private void bindFamily(
            PreparedStatement statement,
            CommandFamilyKey key,
            int index
    ) throws SQLException {
        statement.setString(index, key.ownerId().toString());
        statement.setString(index + 1, key.familyId());
    }

    private void setText(
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

    private void setDouble(
            PreparedStatement statement,
            int index,
            Double value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.REAL);
        } else {
            statement.setDouble(index, value);
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

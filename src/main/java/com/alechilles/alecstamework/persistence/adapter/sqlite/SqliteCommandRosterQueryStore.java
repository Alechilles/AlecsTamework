package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRoster;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Focused normalized command-roster query store. */
final class SqliteCommandRosterQueryStore {
    private static final String MEMBER_COLUMNS = """
            slot_id, profile_id, owner_uuid, family_id,
            membership_revision, group_id, active_for_bulk_commands,
            home_world_key, home_x, home_y, home_z,
            created_at_ms, updated_at_ms
            """;

    private final Connection connection;

    SqliteCommandRosterQueryStore(Connection connection) {
        this.connection = connection;
    }

    Optional<CommandRoster> findRoster(CommandFamilyKey familyKey) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT roster_revision, created_at_ms, updated_at_ms
                FROM command_family
                WHERE owner_uuid = ? AND family_id = ?
                """)) {
            bindFamily(statement, familyKey, 1);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(new CommandRoster(
                        familyKey,
                        row.getLong("roster_revision"),
                        memberships(familyKey),
                        row.getLong("created_at_ms"),
                        row.getLong("updated_at_ms")
                ))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("command_roster_find", failure);
        }
    }

    Optional<CommandRosterMembership> findByProfile(
            ProfileId profileId
    ) {
        return findMember("profile_id", profileId.toString());
    }

    Optional<CommandRosterMembership> findBySlot(
            CommandRosterSlotId slotId
    ) {
        return findMember("slot_id", slotId.toString());
    }

    List<CommandRoster> findAllRosters() {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid, family_id
                FROM command_family
                ORDER BY owner_uuid, family_id
                """);
             ResultSet rows = statement.executeQuery()) {
            ArrayList<CommandRoster> rosters = new ArrayList<>();
            while (rows.next()) {
                rosters.add(findRoster(new CommandFamilyKey(
                        OwnerId.parse(rows.getString("owner_uuid")),
                        rows.getString("family_id")
                )).orElseThrow());
            }
            return List.copyOf(rosters);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("command_roster_find_all", failure);
        }
    }

    private Optional<CommandRosterMembership> findMember(
            String column,
            String value
    ) {
        if (!"profile_id".equals(column) && !"slot_id".equals(column)) {
            throw new IllegalArgumentException(
                    "Unsupported command roster member key"
            );
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + MEMBER_COLUMNS
                        + " FROM command_roster_membership WHERE "
                        + column + " = ?"
        )) {
            statement.setString(1, value);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(readMembership(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure(
                    "command_roster_member_find", failure
            );
        }
    }

    private List<CommandRosterMembership> memberships(
            CommandFamilyKey familyKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + MEMBER_COLUMNS + """
                         FROM command_roster_membership
                         WHERE owner_uuid = ? AND family_id = ?
                         ORDER BY profile_id
                        """
        )) {
            bindFamily(statement, familyKey, 1);
            ArrayList<CommandRosterMembership> memberships =
                    new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    memberships.add(readMembership(rows));
                }
            }
            return List.copyOf(memberships);
        }
    }

    private CommandRosterMembership readMembership(ResultSet row)
            throws SQLException {
        return new CommandRosterMembership(
                CommandRosterSlotId.parse(row.getString("slot_id")),
                new CommandFamilyKey(
                        OwnerId.parse(row.getString("owner_uuid")),
                        row.getString("family_id")
                ),
                ProfileId.parse(row.getString("profile_id")),
                row.getLong("membership_revision"),
                row.getString("group_id"),
                row.getInt("active_for_bulk_commands") != 0,
                readHome(row),
                row.getLong("created_at_ms"),
                row.getLong("updated_at_ms")
        );
    }

    private CommandRosterHome readHome(ResultSet row)
            throws SQLException {
        String world = row.getString("home_world_key");
        return world == null ? null : new CommandRosterHome(
                world,
                row.getDouble("home_x"),
                row.getDouble("home_y"),
                row.getDouble("home_z")
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

    private PersistenceStoreException storeFailure(
            String operation,
            Throwable failure
    ) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }
}

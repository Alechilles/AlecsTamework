package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionIdentityPort;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
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
 * Connection-bound SQLite identity adapter for the replacement persistence lineage.
 *
 * <p>The caller owns the connection and transaction. This adapter never commits, opens another
 * connection, or publishes runtime state.</p>
 */
public final class SqliteCompanionIdentityStore implements CompanionIdentityPort {
    private final Connection connection;

    public SqliteCompanionIdentityStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Identity store connection is required");
        }
        this.connection = connection;
    }

    @Override
    public Optional<CompanionIdentity> findProfile(ProfileId profileId) {
        require(profileId, "Profile ID");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, display_name, role_id, metadata_json, metadata_hash,
                       last_known_world_key, created_at_ms, updated_at_ms,
                       last_active_at_ms, metadata_revision
                FROM companion_profile
                WHERE profile_id = ?
                """)) {
            statement.setString(1, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readProfile(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_find_profile", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionIdentity> createProfile(CompanionIdentity profile) {
        require(profile, "Profile");
        if (profile.metadataRevision() != 0) {
            throw new IllegalArgumentException("New profiles must begin at metadata revision zero");
        }
        Optional<CompanionIdentity> existing = findProfile(profile.profileId());
        if (existing.isPresent()) {
            return existing.get().equals(profile)
                    ? PersistenceMutationResult.applied(existing.get())
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_profile(
                    profile_id, display_name, role_id, metadata_json, metadata_hash,
                    last_known_world_key, created_at_ms, updated_at_ms,
                    last_active_at_ms, metadata_revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindProfile(statement, profile);
            statement.executeUpdate();
            return PersistenceMutationResult.applied(profile);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_create_profile", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionIdentity> updateProfile(
            CompanionIdentity next,
            long expectedRevision
    ) {
        require(next, "Next profile");
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE
                || next.metadataRevision() != expectedRevision + 1) {
            throw new IllegalArgumentException("Profile update must advance the expected revision exactly once");
        }
        CompanionIdentity current = findProfile(next.profileId()).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (current.metadataRevision() != expectedRevision) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        if (current.createdAtMs() != next.createdAtMs()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_profile
                SET display_name = ?, role_id = ?, metadata_json = ?, metadata_hash = ?,
                    last_known_world_key = ?, updated_at_ms = ?, last_active_at_ms = ?,
                    metadata_revision = ?
                WHERE profile_id = ? AND metadata_revision = ?
                """)) {
            setNullableText(statement, 1, next.displayName());
            setNullableText(statement, 2, next.roleId());
            setNullableText(statement, 3, next.metadataJson());
            setNullableText(statement, 4, text(next.metadataHash()));
            setNullableText(statement, 5, next.lastKnownWorldKey());
            statement.setLong(6, next.updatedAtMs());
            statement.setLong(7, next.lastActiveAtMs());
            statement.setLong(8, next.metadataRevision());
            statement.setString(9, next.profileId().toString());
            statement.setLong(10, expectedRevision);
            if (statement.executeUpdate() == 1) {
                return PersistenceMutationResult.applied(next);
            }
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_update_profile", failure);
        }
    }

    @Override
    public Optional<CompanionAlias> resolveAlias(NpcAlias alias) {
        require(alias, "NPC alias");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT npc_uuid, profile_id, alias_generation, alias_state,
                       lease_operation_id, mapped_at_ms, retired_at_ms
                FROM companion_alias
                WHERE npc_uuid = ?
                """)) {
            statement.setString(1, alias.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readAlias(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_resolve_alias", failure);
        }
    }

    /** Reads the complete current and retired alias lineage. */
    public List<CompanionAlias> findAllAliases() {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT npc_uuid, profile_id, alias_generation, alias_state,
                       lease_operation_id, mapped_at_ms, retired_at_ms
                FROM companion_alias
                WHERE alias_state IN ('CURRENT', 'RETIRED')
                ORDER BY profile_id, alias_generation
                """)) {
            ArrayList<CompanionAlias> aliases = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    aliases.add(readAlias(row));
                }
            }
            return List.copyOf(aliases);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_find_all_aliases", failure);
        }
    }

    @Override
    public Optional<CompanionAlias> findCurrentAlias(ProfileId profileId) {
        require(profileId, "Profile ID");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT npc_uuid, profile_id, alias_generation, alias_state,
                       lease_operation_id, mapped_at_ms, retired_at_ms
                FROM companion_alias
                WHERE profile_id = ? AND alias_state = 'CURRENT'
                """)) {
            statement.setString(1, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readAlias(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_find_current_alias", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionAlias> leaseAlias(
            ProfileId profileId,
            NpcAlias alias,
            OperationId operationId,
            long mappedAtMs
    ) {
        require(profileId, "Profile ID");
        require(alias, "NPC alias");
        require(operationId, "Operation ID");
        Optional<CompanionAlias> existing = resolveAlias(alias);
        if (existing.isPresent()) {
            CompanionAlias row = existing.get();
            return row.profileId().equals(profileId)
                    && row.state() == CompanionAlias.State.LEASED
                    && operationId.equals(row.leaseOperationId())
                    ? PersistenceMutationResult.applied(row)
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        if (findProfile(profileId).isEmpty()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (!operationExists(operationId)) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        long generation = nextAliasGeneration(profileId);
        CompanionAlias leased = new CompanionAlias(
                alias, profileId, generation, CompanionAlias.State.LEASED,
                operationId, mappedAtMs, null
        );
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_alias(
                    npc_uuid, profile_id, alias_generation, alias_state,
                    lease_operation_id, mapped_at_ms, retired_at_ms
                ) VALUES (?, ?, ?, 'LEASED', ?, ?, NULL)
                """)) {
            statement.setString(1, alias.toString());
            statement.setString(2, profileId.toString());
            statement.setLong(3, generation);
            statement.setString(4, operationId.toString());
            statement.setLong(5, mappedAtMs);
            statement.executeUpdate();
            return PersistenceMutationResult.applied(leased);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_lease_alias", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionAlias> promoteAlias(
            NpcAlias alias,
            OperationId operationId,
            long promotedAtMs
    ) {
        require(alias, "NPC alias");
        require(operationId, "Operation ID");
        Optional<CompanionAlias> existing = resolveAlias(alias);
        if (existing.isEmpty()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        CompanionAlias row = existing.get();
        if (!operationId.equals(row.leaseOperationId())) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        if (row.state() == CompanionAlias.State.CURRENT) {
            return PersistenceMutationResult.applied(row);
        }
        if (row.state() != CompanionAlias.State.LEASED) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try {
            retireOtherCurrentAlias(row.profileId(), alias, promotedAtMs);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE companion_alias
                    SET alias_state = 'CURRENT'
                    WHERE npc_uuid = ? AND alias_state = 'LEASED' AND lease_operation_id = ?
                    """)) {
                statement.setString(1, alias.toString());
                statement.setString(2, operationId.toString());
                if (statement.executeUpdate() != 1) {
                    return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
                }
            }
            return PersistenceMutationResult.applied(new CompanionAlias(
                    alias, row.profileId(), row.generation(), CompanionAlias.State.CURRENT,
                    operationId, row.mappedAtMs(), null
            ));
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_promote_alias", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionAlias> retireAlias(NpcAlias alias, long retiredAtMs) {
        require(alias, "NPC alias");
        Optional<CompanionAlias> existing = resolveAlias(alias);
        if (existing.isEmpty()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        CompanionAlias row = existing.get();
        if (row.state() == CompanionAlias.State.RETIRED) {
            return PersistenceMutationResult.applied(row);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_alias
                SET alias_state = 'RETIRED', retired_at_ms = ?
                WHERE npc_uuid = ? AND alias_state != 'RETIRED'
                """)) {
            statement.setLong(1, retiredAtMs);
            statement.setString(2, alias.toString());
            statement.executeUpdate();
            return PersistenceMutationResult.applied(new CompanionAlias(
                    alias, row.profileId(), row.generation(), CompanionAlias.State.RETIRED,
                    row.leaseOperationId(), row.mappedAtMs(), retiredAtMs
            ));
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_retire_alias", failure);
        }
    }

    private long nextAliasGeneration(ProfileId profileId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT MAX(alias_generation) FROM companion_alias WHERE profile_id = ?
                """)) {
            statement.setString(1, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                long current = row.getLong(1);
                if (row.wasNull()) {
                    return 0;
                }
                if (current == Long.MAX_VALUE) {
                    throw new IllegalStateException("Alias generation exhausted");
                }
                return current + 1;
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_next_alias_generation", failure);
        }
    }

    private boolean operationExists(OperationId operationId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM operation_envelope WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("identity_find_operation_fence", failure);
        }
    }

    private void retireOtherCurrentAlias(ProfileId profileId,
                                         NpcAlias promoted,
                                         long retiredAtMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_alias
                SET alias_state = 'RETIRED', retired_at_ms = ?
                WHERE profile_id = ? AND alias_state = 'CURRENT' AND npc_uuid != ?
                """)) {
            statement.setLong(1, retiredAtMs);
            statement.setString(2, profileId.toString());
            statement.setString(3, promoted.toString());
            statement.executeUpdate();
        }
    }

    private CompanionIdentity readProfile(ResultSet row) throws SQLException {
        return new CompanionIdentity(
                ProfileId.parse(row.getString("profile_id")),
                row.getString("display_name"),
                row.getString("role_id"),
                row.getString("metadata_json"),
                parseHash(row.getString("metadata_hash")),
                row.getString("last_known_world_key"),
                row.getLong("created_at_ms"),
                row.getLong("updated_at_ms"),
                row.getLong("last_active_at_ms"),
                row.getLong("metadata_revision")
        );
    }

    private CompanionAlias readAlias(ResultSet row) throws SQLException {
        String operationId = row.getString("lease_operation_id");
        long retiredAt = row.getLong("retired_at_ms");
        Long nullableRetiredAt = row.wasNull() ? null : retiredAt;
        return new CompanionAlias(
                NpcAlias.parse(row.getString("npc_uuid")),
                ProfileId.parse(row.getString("profile_id")),
                row.getLong("alias_generation"),
                CompanionAlias.State.valueOf(row.getString("alias_state")),
                operationId == null ? null : OperationId.parse(operationId),
                row.getLong("mapped_at_ms"),
                nullableRetiredAt
        );
    }

    private void bindProfile(PreparedStatement statement, CompanionIdentity profile)
            throws SQLException {
        statement.setString(1, profile.profileId().toString());
        setNullableText(statement, 2, profile.displayName());
        setNullableText(statement, 3, profile.roleId());
        setNullableText(statement, 4, profile.metadataJson());
        setNullableText(statement, 5, text(profile.metadataHash()));
        setNullableText(statement, 6, profile.lastKnownWorldKey());
        statement.setLong(7, profile.createdAtMs());
        statement.setLong(8, profile.updatedAtMs());
        statement.setLong(9, profile.lastActiveAtMs());
        statement.setLong(10, profile.metadataRevision());
    }

    private void setNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private Sha256Hash parseHash(String value) {
        return value == null ? null : Sha256Hash.parse(value);
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

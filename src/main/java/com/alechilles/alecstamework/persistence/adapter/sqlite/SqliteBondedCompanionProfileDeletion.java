package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Owns the deliberately irreversible, revision-fenced profile deletion path.
 *
 * <p>An ACTIVE profile is never eligible here. Its projection must first be
 * synchronously removed, so a foreign-key cascade cannot discard the sole
 * cleanup intent for a live NPC.</p>
 */
final class SqliteBondedCompanionProfileDeletion {
    private final SqliteConnectionFactory connections;
    private final SqliteBondedCompanionMapper mapper;

    SqliteBondedCompanionProfileDeletion(
            @Nonnull SqliteConnectionFactory connections,
            @Nonnull SqliteBondedCompanionMapper mapper
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Nonnull
    BondedCompanionStoreResult<BondedCompanionRecord.Profile> delete(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        String roster = text(rosterId, "rosterId");
        String profile = text(profileId, "profileId");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision cannot be negative");
        }
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            SqliteBondedCompanionProfileRow current =
                    new SqliteBondedCompanionProfileReader(connection)
                            .require(profile);
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> denied =
                    denied(current, ownerUuid, roster, expectedRevision);
            if (denied != null) {
                connection.rollback();
                return denied;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM bonded_companion_profile
                    WHERE profile_id = ? AND owner_uuid = ? AND roster_id = ?
                      AND revision = ? AND state <> 'ACTIVE'
                    """)) {
                statement.setString(1, profile);
                statement.setString(2, ownerUuid.toString());
                statement.setString(3, roster);
                statement.setLong(4, expectedRevision);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return result(BondedCompanionStoreResult.Code.REVISION_CONFLICT,
                            mapper.toDomain(current),
                            "bonded-profile-revision-conflict");
                }
            }
            connection.commit();
            return result(BondedCompanionStoreResult.Code.APPLIED,
                    mapper.toDomain(current), null);
        } catch (SQLException failure) {
            rollback(connection);
            String reason = failure.getMessage() != null
                    && failure.getMessage().contains("bonded_profile_disappeared")
                    ? "bonded-profile-not-found" : "bonded-profile-delete-failed";
            BondedCompanionStoreResult.Code code = "bonded-profile-not-found"
                    .equals(reason) ? BondedCompanionStoreResult.Code.NOT_FOUND
                    : BondedCompanionStoreResult.Code.STORAGE_FAILURE;
            return result(code, null, reason);
        } finally {
            close(connection);
        }
    }

    private BondedCompanionStoreResult<BondedCompanionRecord.Profile> denied(
            SqliteBondedCompanionProfileRow current,
            UUID ownerUuid,
            String rosterId,
            long expectedRevision
    ) {
        BondedCompanionRecord.Profile profile = mapper.toDomain(current);
        if (!ownerUuid.equals(current.ownerUuid())
                || !rosterId.equals(current.rosterId())) {
            return result(BondedCompanionStoreResult.Code.NOT_OWNER, profile,
                    "bonded-profile-not-owned");
        }
        if (current.revision() != expectedRevision) {
            return result(BondedCompanionStoreResult.Code.REVISION_CONFLICT,
                    profile, "bonded-profile-revision-conflict");
        }
        if (current.state() == BondedCompanionState.ACTIVE) {
            return result(BondedCompanionStoreResult.Code.INVALID_STATE,
                    profile, "bonded-profile-delete-requires-stored");
        }
        return null;
    }

    private BondedCompanionStoreResult<BondedCompanionRecord.Profile> result(
            BondedCompanionStoreResult.Code code,
            BondedCompanionRecord.Profile value,
            String reason
    ) {
        return new BondedCompanionStoreResult<>(code, value, reason, false);
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static void rollback(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original storage outcome is authoritative.
        }
    }

    private static void close(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The transaction is already complete or has been rolled back.
        }
    }
}

package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ClaimStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryClaim;

/** Fail-closed authorization boundary between a durable lost envelope and a recovery spawn claim. */
final class NpcRecoveryClaimAuthorizer {
    private final LostRecoveryEnvelopeCodec envelopeCodec = new LostRecoveryEnvelopeCodec();

    @Nullable
    ClaimStatus authorize(@Nonnull Connection connection,
                          @Nonnull RecoveryClaim claim) throws SQLException {
        if (claim.sourceNpcUuid() == null) {
            return ClaimStatus.SOURCE_CONFLICT;
        }
        ProfileRow profile = loadProfile(connection, claim.profileId());
        if (profile == null) {
            return ClaimStatus.PROFILE_NOT_FOUND;
        }
        if (!claim.sourceNpcUuid().equals(profile.currentNpcUuid())) {
            return ClaimStatus.SOURCE_CONFLICT;
        }
        if (profileStateBlocksRecovery(connection, claim.profileId())) {
            return ClaimStatus.PROFILE_STATE_CONFLICT;
        }
        return authorizeLostEnvelope(connection, claim);
    }

    @Nullable
    private ClaimStatus authorizeLostEnvelope(@Nonnull Connection connection,
                                              @Nonnull RecoveryClaim claim) throws SQLException {
        String payloadJson = loadSoleActiveLostPayload(connection, claim.profileId());
        if (payloadJson == null) {
            return ClaimStatus.LOST_SNAPSHOT_CONFLICT;
        }
        LostRecoveryEnvelopeCodec.DecodeResult decoded = envelopeCodec.decode(payloadJson);
        if (decoded.status() == LostRecoveryEnvelopeCodec.Status.FAILED
                || decoded.payload() == null) {
            return ClaimStatus.LOST_ENVELOPE_INVALID;
        }
        if (decoded.status() == LostRecoveryEnvelopeCodec.Status.LEGACY_UNVERIFIED) {
            return ClaimStatus.LOST_ENVELOPE_UNVERIFIED;
        }
        LostRecoveryEnvelopeCodec.DecodedPayload payload = decoded.payload();
        if (payload.metadata().replacementNpcUuid() != null) {
            return ClaimStatus.LOST_NOT_AWAITING;
        }
        if (!claim.sourceNpcUuid().equals(payload.sourceNpcUuid())) {
            return ClaimStatus.SOURCE_CONFLICT;
        }
        if (payload.formatVersion() != LostRecoveryEnvelopeCodec.CURRENT_FORMAT_VERSION
                || payload.fullSnapshot() == null
                || payload.fullSnapshotSha256() == null
                || payload.fullSnapshotSha256().isBlank()) {
            return ClaimStatus.LOST_ENVELOPE_UNVERIFIED;
        }
        if (payload.fullSnapshot().roleId() == null
                || payload.fullSnapshot().roleId().isBlank()) {
            return ClaimStatus.LOST_ENVELOPE_INVALID;
        }
        return claim.sourceNpcUuid().equals(payload.fullSnapshot().npcUuid())
                ? null
                : ClaimStatus.SOURCE_CONFLICT;
    }

    @Nullable
    private ProfileRow loadProfile(@Nonnull Connection connection,
                                   @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = ? LIMIT 2")) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                UUID currentNpcUuid = parseUuid(resultSet.getString("current_npc_uuid"));
                if (resultSet.next()) {
                    throw integrity("multiple_profile_rows:" + profileId);
                }
                return new ProfileRow(currentNpcUuid);
            }
        }
    }

    private boolean profileStateBlocksRecovery(@Nonnull Connection connection,
                                               @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT capture_active, death_active, lost_active, in_coop
                FROM profile_states WHERE profile_id = ? LIMIT 2
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return true;
                }
                boolean blocked = readBoolean(resultSet, "capture_active", profileId)
                        || readBoolean(resultSet, "death_active", profileId)
                        || !readBoolean(resultSet, "lost_active", profileId)
                        || readBoolean(resultSet, "in_coop", profileId);
                if (resultSet.next()) {
                    throw integrity("multiple_profile_state_rows:" + profileId);
                }
                return blocked
                        || hasActiveManagedResident(connection, profileId)
                        || hasActiveCoopLifecycle(connection, profileId);
            }
        }
    }

    @Nullable
    private String loadSoleActiveLostPayload(@Nonnull Connection connection,
                                             @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_json FROM npc_snapshots
                WHERE profile_id = ? AND snapshot_type = 'lost' AND is_active = 1
                ORDER BY created_at_ms DESC, snapshot_id LIMIT 2
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String payload = resultSet.getString("payload_json");
                if (resultSet.next()) {
                    return null;
                }
                return payload;
            }
        }
    }

    private boolean hasActiveManagedResident(@Nonnull Connection connection,
                                             @Nonnull String profileId) throws SQLException {
        return hasActiveRow(connection,
                "SELECT resident_id FROM managed_coop_residents WHERE profile_id = ? "
                        + "AND active = 1 ORDER BY resident_id LIMIT 2",
                profileId);
    }

    private boolean hasActiveCoopLifecycle(@Nonnull Connection connection,
                                           @Nonnull String profileId) throws SQLException {
        return hasActiveRow(connection,
                "SELECT operation_id FROM coop_lifecycle_operations WHERE profile_id = ? "
                        + "AND active = 1 ORDER BY operation_id LIMIT 2",
                profileId);
    }

    private boolean hasActiveRow(@Nonnull Connection connection,
                                 @Nonnull String sql,
                                 @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                if (resultSet.next()) {
                    throw integrity("multiple_active_profile_lifecycle_rows:" + profileId);
                }
                return true;
            }
        }
    }

    private boolean readBoolean(@Nonnull ResultSet resultSet,
                                @Nonnull String column,
                                @Nonnull String profileId) throws SQLException {
        int value = resultSet.getInt(column);
        if (value != 0 && value != 1) {
            throw integrity("invalid_profile_state_boolean:" + profileId + ":" + column + "=" + value);
        }
        return value == 1;
    }

    @Nullable
    private UUID parseUuid(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private NpcRecoveryOperationTransactions.RepositoryIntegrityException integrity(String message) {
        return new NpcRecoveryOperationTransactions.RepositoryIntegrityException(message);
    }

    private record ProfileRow(@Nullable UUID currentNpcUuid) {
    }
}

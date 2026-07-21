package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads a prior full recovery envelope only when durable identity and lifecycle evidence proves
 * that its replacement is the profile's current unloaded projection.
 */
final class RecoveredProjectionSnapshotStore {
    private static final String UNLOADED = "UNLOADED";
    private final SqliteConnectionManager connectionManager;
    private final LostRecoveryEnvelopeCodec envelopeCodec = new LostRecoveryEnvelopeCodec();

    RecoveredProjectionSnapshotStore(@Nonnull SqliteConnectionManager connectionManager) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
    }

    @Nonnull
    RecoveredProjectionSnapshotLoadResult load(@Nullable UUID currentNpcUuid) {
        if (currentNpcUuid == null) {
            return RecoveredProjectionSnapshotLoadResult.failed(
                    null, null, "current_uuid_required", null);
        }
        try (Connection connection = connectionManager.openConnection()) {
            CandidateRow row = loadCandidate(connection, currentNpcUuid);
            if (row == null) {
                return RecoveredProjectionSnapshotLoadResult.notFound("current_profile_not_found");
            }
            String rowConflict = validateCandidateRow(row);
            if (rowConflict != null) {
                return RecoveredProjectionSnapshotLoadResult.conflict(
                        row.profileId(), null, rowConflict);
            }
            return decodeAndVerify(connection, row, currentNpcUuid);
        } catch (IntegrityFailure failure) {
            return RecoveredProjectionSnapshotLoadResult.failed(
                    failure.profileId, failure.sourceNpcUuid, failure.getMessage(), failure);
        } catch (Exception exception) {
            return RecoveredProjectionSnapshotLoadResult.failed(
                    null, null, "recovered_projection_snapshot_read_failed", exception);
        }
    }

    @Nonnull
    private RecoveredProjectionSnapshotLoadResult decodeAndVerify(
            @Nonnull Connection connection,
            @Nonnull CandidateRow row,
            @Nonnull UUID currentNpcUuid) throws Exception {
        LostRecoveryEnvelopeCodec.DecodeResult decoded = envelopeCodec.decode(row.payloadJson());
        if (decoded.status() == LostRecoveryEnvelopeCodec.Status.FAILED || decoded.payload() == null) {
            String reason = decoded.failure() != null
                    ? "lost_envelope_" + decoded.failure().name().toLowerCase(java.util.Locale.ROOT)
                    : "lost_envelope_decode_failed";
            return RecoveredProjectionSnapshotLoadResult.failed(
                    row.profileId(), null, reason, null);
        }
        LostRecoveryEnvelopeCodec.DecodedPayload payload = decoded.payload();
        UUID sourceNpcUuid = payload.sourceNpcUuid();
        UUID replacementNpcUuid = payload.metadata().replacementNpcUuid();
        if (replacementNpcUuid == null) {
            return RecoveredProjectionSnapshotLoadResult.notFound("lost_envelope_not_recovered");
        }
        if (!currentNpcUuid.equals(replacementNpcUuid)) {
            return RecoveredProjectionSnapshotLoadResult.conflict(
                    row.profileId(), sourceNpcUuid, "replacement_not_profile_current");
        }
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot = payload.fullSnapshot();
        if (sourceNpcUuid == null || sourceNpcUuid.equals(currentNpcUuid)) {
            return RecoveredProjectionSnapshotLoadResult.conflict(
                    row.profileId(), sourceNpcUuid, "recovery_source_not_historical");
        }
        if (decoded.status() != LostRecoveryEnvelopeCodec.Status.FOUND
                || snapshot == null
                || payload.fullSnapshotSha256() == null
                || !sourceNpcUuid.equals(snapshot.npcUuid())) {
            return RecoveredProjectionSnapshotLoadResult.failed(
                    row.profileId(), sourceNpcUuid, "verified_full_snapshot_unavailable", null);
        }
        String aliasConflict = validateHistoricalAlias(
                connection, row.profileId(), sourceNpcUuid);
        if (aliasConflict != null) {
            return RecoveredProjectionSnapshotLoadResult.conflict(
                    row.profileId(), sourceNpcUuid, aliasConflict);
        }
        String operationConflict = validateFinalizedRecovery(
                connection, row.profileId(), sourceNpcUuid, currentNpcUuid);
        if (operationConflict != null) {
            return RecoveredProjectionSnapshotLoadResult.conflict(
                    row.profileId(), sourceNpcUuid, operationConflict);
        }
        return RecoveredProjectionSnapshotLoadResult.found(
                row.profileId(), sourceNpcUuid, snapshot);
    }

    @Nullable
    private CandidateRow loadCandidate(@Nonnull Connection connection,
                                       @Nonnull UUID currentNpcUuid) throws Exception {
        String sql = """
                SELECT p.profile_id, s.snapshot_id, s.payload_json,
                       st.profile_id AS state_profile_id,
                       st.capture_active, st.death_active, st.lost_active, st.in_coop,
                       pop.profile_id AS population_profile_id, pop.lifecycle_state,
                       (SELECT COUNT(*) FROM npc_uuid_aliases a
                         WHERE a.profile_id = p.profile_id AND a.npc_uuid = p.current_npc_uuid
                           AND a.is_current = 1) AS current_alias_count,
                       (SELECT COUNT(*) FROM npc_recovery_operations active_recovery
                         WHERE active_recovery.profile_id = p.profile_id
                           AND active_recovery.active = 1) AS active_recovery_count,
                       (SELECT COUNT(*) FROM managed_coop_residents resident
                         WHERE resident.profile_id = p.profile_id
                           AND resident.active = 1) AS active_coop_count,
                       (SELECT COUNT(*) FROM companion_population_operations operation
                         WHERE operation.profile_id = p.profile_id
                           AND operation.state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING'))
                           AS active_population_operation_count
                FROM npc_profiles p
                LEFT JOIN npc_snapshots s ON s.profile_id = p.profile_id
                    AND s.snapshot_type = 'lost' AND s.is_active = 1
                LEFT JOIN profile_states st ON st.profile_id = p.profile_id
                LEFT JOIN companion_population_state pop ON pop.profile_id = p.profile_id
                WHERE p.current_npc_uuid = ?
                ORDER BY s.created_at_ms DESC, s.snapshot_id
                LIMIT 2
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, currentNpcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                CandidateRow row = readCandidate(resultSet);
                if (resultSet.next()) {
                    throw integrity(row.profileId(), null, "multiple_active_lost_snapshots");
                }
                return row;
            }
        }
    }

    @Nonnull
    private CandidateRow readCandidate(@Nonnull ResultSet resultSet) throws SQLException {
        long snapshotId = resultSet.getLong("snapshot_id");
        boolean snapshotPresent = !resultSet.wasNull();
        return new CandidateRow(
                resultSet.getString("profile_id"),
                snapshotPresent ? snapshotId : null,
                resultSet.getString("payload_json"),
                resultSet.getString("state_profile_id") != null,
                readBoolean(resultSet, "capture_active"),
                readBoolean(resultSet, "death_active"),
                readBoolean(resultSet, "lost_active"),
                readBoolean(resultSet, "in_coop"),
                resultSet.getString("population_profile_id") != null,
                resultSet.getString("lifecycle_state"),
                resultSet.getInt("current_alias_count"),
                resultSet.getInt("active_recovery_count"),
                resultSet.getInt("active_coop_count"),
                resultSet.getInt("active_population_operation_count")
        );
    }

    @Nullable
    private String validateCandidateRow(@Nonnull CandidateRow row) {
        if (row.snapshotId() == null || row.payloadJson() == null) {
            return "active_recovered_lost_envelope_missing";
        }
        if (!row.profileStatePresent()) {
            return "profile_state_missing";
        }
        if (row.captureActive() || row.deathActive() || row.inCoop() || !row.lostActive()) {
            return "profile_lifecycle_conflict";
        }
        if (!row.populationStatePresent() || !UNLOADED.equals(row.lifecycleState())) {
            return "population_not_unloaded";
        }
        if (row.currentAliasCount() != 1) {
            return "current_alias_not_unique";
        }
        if (row.activeRecoveryCount() != 0) {
            return "active_recovery_conflict";
        }
        if (row.activeCoopCount() != 0) {
            return "active_coop_conflict";
        }
        if (row.activePopulationOperationCount() != 0) {
            return "active_population_operation_conflict";
        }
        return null;
    }

    @Nullable
    private String validateHistoricalAlias(@Nonnull Connection connection,
                                           @Nonnull String profileId,
                                           @Nonnull UUID sourceNpcUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, is_current FROM npc_uuid_aliases
                WHERE npc_uuid = ? ORDER BY profile_id LIMIT 2
                """)) {
            statement.setString(1, sourceNpcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return "recovery_source_alias_missing";
                }
                String aliasProfileId = resultSet.getString("profile_id");
                boolean current = readRequiredBoolean(resultSet, "is_current");
                if (resultSet.next()) {
                    return "recovery_source_alias_ambiguous";
                }
                if (!profileId.equals(aliasProfileId) || current) {
                    return "recovery_source_alias_conflict";
                }
                return null;
            }
        }
    }

    @Nullable
    private String validateFinalizedRecovery(@Nonnull Connection connection,
                                             @Nonnull String profileId,
                                             @Nonnull UUID sourceNpcUuid,
                                             @Nonnull UUID currentNpcUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT planned_target_uuid, actual_target_uuid
                FROM npc_recovery_operations
                WHERE profile_id = ? AND source_npc_uuid = ?
                  AND state = 'FINALIZED' AND active = 0
                ORDER BY completed_at_ms DESC, operation_id LIMIT 2
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, sourceNpcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return "finalized_recovery_evidence_missing";
                }
                UUID plannedTarget = parseUuid(
                        resultSet.getString("planned_target_uuid"), "planned_target_uuid");
                UUID actualTarget = parseUuid(
                        resultSet.getString("actual_target_uuid"), "actual_target_uuid");
                if (resultSet.next()) {
                    return "finalized_recovery_evidence_ambiguous";
                }
                if (!currentNpcUuid.equals(plannedTarget) || !currentNpcUuid.equals(actualTarget)) {
                    return "finalized_recovery_target_conflict";
                }
                return null;
            }
        }
    }

    private boolean readBoolean(@Nonnull ResultSet resultSet, @Nonnull String column)
            throws SQLException {
        int value = resultSet.getInt(column);
        if (resultSet.wasNull()) {
            return false;
        }
        if (value != 0 && value != 1) {
            throw integrity(null, null, "invalid_boolean_" + column);
        }
        return value == 1;
    }

    private boolean readRequiredBoolean(@Nonnull ResultSet resultSet, @Nonnull String column)
            throws SQLException {
        int value = resultSet.getInt(column);
        if (resultSet.wasNull() || value != 0 && value != 1) {
            throw integrity(null, null, "invalid_boolean_" + column);
        }
        return value == 1;
    }

    @Nonnull
    private UUID parseUuid(@Nullable String raw, @Nonnull String field) {
        try {
            return UUID.fromString(raw == null ? "" : raw);
        } catch (IllegalArgumentException exception) {
            throw integrity(null, null, "invalid_" + field);
        }
    }

    @Nonnull
    private IntegrityFailure integrity(@Nullable String profileId,
                                       @Nullable UUID sourceNpcUuid,
                                       @Nonnull String reason) {
        return new IntegrityFailure(profileId, sourceNpcUuid, reason);
    }

    private record CandidateRow(
            @Nonnull String profileId,
            @Nullable Long snapshotId,
            @Nullable String payloadJson,
            boolean profileStatePresent,
            boolean captureActive,
            boolean deathActive,
            boolean lostActive,
            boolean inCoop,
            boolean populationStatePresent,
            @Nullable String lifecycleState,
            int currentAliasCount,
            int activeRecoveryCount,
            int activeCoopCount,
            int activePopulationOperationCount) {
    }

    private static final class IntegrityFailure extends RuntimeException {
        private final String profileId;
        private final UUID sourceNpcUuid;

        private IntegrityFailure(@Nullable String profileId,
                                 @Nullable UUID sourceNpcUuid,
                                 @Nonnull String reason) {
            super(reason);
            this.profileId = profileId;
            this.sourceNpcUuid = sourceNpcUuid;
        }
    }
}

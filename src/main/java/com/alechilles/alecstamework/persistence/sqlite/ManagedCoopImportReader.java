package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionEnvelope;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceRecord;

/** Fail-closed mapper for immutable managed-coop import journal rows. */
final class ManagedCoopImportReader {
    private static final String SESSION_COLUMNS = """
            session_id, authority_id, world_name, coop_id, x, y, z,
            audit_version, audit_fingerprint, audit_envelope_json, audit_envelope_hash,
            layout_id, coop_asset_id, resident_list_class_name, produce_payload,
            produce_fingerprint, source_count, state, active, begin_command_id,
            final_command_id, created_at_ms, updated_at_ms, finalized_at_ms, last_error
            """;
    private static final String SOURCE_COLUMNS = """
            source_id, session_id, source_fingerprint, source_envelope_json,
            source_envelope_hash, source_payload, source_payload_hash, locator_hints_json,
            locator_hints_hash, source_slot, source_order, metadata_present,
            persistent_ref_present, persistent_uuid, deployed_to_world, last_produced,
            profile_at_audit_id, role_id, display_name, managed_snapshot_json,
            managed_snapshot_hash, managed_snapshot_version, unavailable_fields_json,
            disposition_kind, disposition_command_id, operation_id, resident_id, profile_id,
            conflict_id, conflict_kind, neutralization_state, neutralization_command_id,
            absence_proof_json, absence_proof_hash, absence_proof_version,
            created_at_ms, disposition_at_ms,
            verified_absent_at_ms
            """;

    @Nullable
    SessionRecord loadActive(Connection connection,
                             ManagedCoopAuthorityKey key,
                             String coopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SESSION_COLUMNS + " FROM managed_coop_import_sessions "
                        + "WHERE authority_id = ? AND lower(coop_id) = lower(?) AND active = 1 LIMIT 2")) {
            statement.setString(1, key.authorityId());
            statement.setString(2, coopId.trim());
            SessionRecord record = loadOne(statement, "duplicate_active_managed_coop_import_session");
            if (record != null && !record.envelope().authorityKey().equals(key)) {
                throw integrity("managed_coop_import_authority_key_mismatch:" + record.envelope().sessionId());
            }
            return record;
        }
    }

    @Nullable
    SessionRecord loadById(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SESSION_COLUMNS + " FROM managed_coop_import_sessions "
                        + "WHERE session_id = ? LIMIT 2")) {
            statement.setString(1, sessionId);
            return loadOne(statement, "duplicate_managed_coop_import_session_id");
        }
    }

    @Nullable
    SourceRecord loadSource(Connection connection, String sourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SOURCE_COLUMNS + " FROM managed_coop_import_sources "
                        + "WHERE source_id = ? LIMIT 2")) {
            statement.setString(1, sourceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                SourceRecord record = mapSource(resultSet);
                if (resultSet.next()) {
                    throw integrity("duplicate_managed_coop_import_source_id:" + sourceId);
                }
                return record;
            }
        }
    }

    List<SourceRecord> loadSources(Connection connection, String sessionId) throws SQLException {
        ArrayList<SourceRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SOURCE_COLUMNS + " FROM managed_coop_import_sources "
                        + "WHERE session_id = ? ORDER BY source_order, source_id")) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    SourceRecord record = mapSource(resultSet);
                    if (!record.sessionId().equals(sessionId)) {
                        throw integrity("managed_coop_import_source_session_mismatch:"
                                + record.evidence().sourceId());
                    }
                    records.add(record);
                }
            }
        }
        SessionRecord session = loadById(connection, sessionId);
        if (session == null || session.sourceCount() != records.size()) {
            throw integrity("managed_coop_import_source_count_mismatch:" + sessionId);
        }
        return List.copyOf(records);
    }

    private SessionRecord loadOne(PreparedStatement statement, String duplicateReason)
            throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            SessionRecord record = mapSession(resultSet);
            if (resultSet.next()) {
                throw integrity(duplicateReason);
            }
            return record;
        }
    }

    private SessionRecord mapSession(ResultSet resultSet) throws SQLException {
        try {
            String sessionId = required(resultSet, "session_id");
            String authorityId = required(resultSet, "authority_id");
            ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey(
                    required(resultSet, "world_name"),
                    resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z"));
            if (!authorityId.equals(key.authorityId())) {
                throw integrity("managed_coop_import_authority_id_mismatch:" + sessionId);
            }
            long createdAtMs = resultSet.getLong("created_at_ms");
            SessionEnvelope envelope = new SessionEnvelope(
                    sessionId, key, required(resultSet, "coop_id"),
                    resultSet.getInt("audit_version"), required(resultSet, "audit_fingerprint"),
                    requiredRaw(resultSet, "audit_envelope_json"),
                    required(resultSet, "audit_envelope_hash"), required(resultSet, "layout_id"),
                    optional(resultSet.getString("coop_asset_id")),
                    required(resultSet, "resident_list_class_name"),
                    requiredRaw(resultSet, "produce_payload"),
                    required(resultSet, "produce_fingerprint"),
                    required(resultSet, "begin_command_id"), createdAtMs);
            SessionState state = enumValue(
                    SessionState.class, resultSet.getString("state"), "import_session_state");
            boolean active = strictBoolean(resultSet, "active");
            String finalCommandId = optional(resultSet.getString("final_command_id"));
            long finalizedAtMs = resultSet.getLong("finalized_at_ms");
            validateSessionState(sessionId, state, active, finalCommandId, finalizedAtMs);
            return new SessionRecord(
                    envelope, resultSet.getInt("source_count"), state, active,
                    finalCommandId, resultSet.getLong("updated_at_ms"), finalizedAtMs,
                    resultSet.getString("last_error"));
        } catch (IllegalArgumentException exception) {
            throw integrity("invalid_managed_coop_import_session_row", exception);
        }
    }

    private SourceRecord mapSource(ResultSet resultSet) throws SQLException {
        try {
            String sourceId = required(resultSet, "source_id");
            String sessionId = required(resultSet, "session_id");
            UUID persistentUuid = ManagedCoopReadValidation.optionalUuid(
                    resultSet.getString("persistent_uuid"), "persistent_uuid");
            SourceEvidence evidence = new SourceEvidence(
                    sourceId, required(resultSet, "source_fingerprint"),
                    requiredRaw(resultSet, "source_envelope_json"),
                    required(resultSet, "source_envelope_hash"),
                    requiredRaw(resultSet, "source_payload"),
                    required(resultSet, "source_payload_hash"),
                    requiredRaw(resultSet, "locator_hints_json"),
                    required(resultSet, "locator_hints_hash"),
                    resultSet.getInt("source_slot"), resultSet.getInt("source_order"),
                    strictBoolean(resultSet, "metadata_present"),
                    strictBoolean(resultSet, "persistent_ref_present"), persistentUuid,
                    strictBoolean(resultSet, "deployed_to_world"),
                    optional(resultSet.getString("last_produced")),
                    optional(resultSet.getString("profile_at_audit_id")),
                    optional(resultSet.getString("role_id")),
                    optional(resultSet.getString("display_name")),
                    requiredRaw(resultSet, "managed_snapshot_json"),
                    required(resultSet, "managed_snapshot_hash"),
                    resultSet.getInt("managed_snapshot_version"),
                    requiredRaw(resultSet, "unavailable_fields_json"));
            DispositionKind disposition = optionalEnum(
                    DispositionKind.class, resultSet.getString("disposition_kind"), "disposition_kind");
            NeutralizationState neutralization = enumValue(
                    NeutralizationState.class, resultSet.getString("neutralization_state"),
                    "neutralization_state");
            SourceRecord record = new SourceRecord(
                    sessionId, evidence, disposition,
                    optional(resultSet.getString("disposition_command_id")),
                    optional(resultSet.getString("operation_id")),
                    optional(resultSet.getString("resident_id")),
                    optional(resultSet.getString("profile_id")),
                    optional(resultSet.getString("conflict_id")),
                    optional(resultSet.getString("conflict_kind")), neutralization,
                    optional(resultSet.getString("neutralization_command_id")),
                    resultSet.getString("absence_proof_json"),
                    optional(resultSet.getString("absence_proof_hash")),
                    resultSet.getInt("absence_proof_version"),
                    resultSet.getLong("created_at_ms"),
                    resultSet.getLong("disposition_at_ms"),
                    resultSet.getLong("verified_absent_at_ms"));
            validateSourceState(record);
            return record;
        } catch (IllegalArgumentException exception) {
            throw integrity("invalid_managed_coop_import_source_row", exception);
        }
    }

    private void validateSessionState(String sessionId,
                                      SessionState state,
                                      boolean active,
                                      @Nullable String finalCommandId,
                                      long finalizedAtMs) throws ManagedCoopIntegrityException {
        if (state == SessionState.ACTIVE) {
            if (!active || finalCommandId != null || finalizedAtMs != 0L) {
                throw integrity("invalid_active_managed_coop_import_session:" + sessionId);
            }
            return;
        }
        if (active || finalCommandId == null || finalizedAtMs == 0L) {
            throw integrity("invalid_finalized_managed_coop_import_session:" + sessionId);
        }
        ManagedCoopImportValidation.hash(finalCommandId, "finalCommandId");
    }

    private void validateSourceState(SourceRecord record) throws ManagedCoopIntegrityException {
        if (record.createdAtMs() == 0L) {
            throw integrity("invalid_managed_coop_import_source_timestamp:"
                    + record.evidence().sourceId());
        }
        if (record.disposition() == null) {
            if (record.neutralizationState() != NeutralizationState.NOT_AUTHORIZED
                    || record.dispositionAtMs() != 0L) {
                throw integrity("invalid_pending_import_source:" + record.evidence().sourceId());
            }
            return;
        }
        ManagedCoopImportValidation.dispositionShape(
                record.disposition(), record.operationId(), record.residentId(),
                record.profileId(), record.conflictId(), record.conflictKind());
        ManagedCoopImportValidation.hash(record.dispositionCommandId(), "dispositionCommandId");
        if (record.dispositionAtMs() == 0L) {
            throw integrity("missing_import_source_disposition_timestamp:"
                    + record.evidence().sourceId());
        }
        if (record.disposition() == DispositionKind.QUARANTINED) {
            if (record.neutralizationState() != NeutralizationState.NOT_REQUIRED) {
                throw integrity("quarantined_import_source_authorized_neutralization:"
                        + record.evidence().sourceId());
            }
            return;
        }
        if (record.neutralizationState() == NeutralizationState.VERIFIED_ABSENT) {
            ManagedCoopImportValidation.hash(
                    record.neutralizationCommandId(), "neutralizationCommandId");
            ManagedCoopImportValidation.contentHash(
                    record.absenceProofHash(), record.absenceProofJson(), "absenceProofHash");
            if (record.absenceProofVersion() < 1 || record.verifiedAbsentAtMs() == 0L) {
                throw integrity("invalid_import_source_absence_proof:"
                        + record.evidence().sourceId());
            }
        } else if (record.neutralizationState() != NeutralizationState.AUTHORIZED) {
            throw integrity("invalid_import_source_neutralization_state:"
                    + record.evidence().sourceId());
        }
    }

    private String required(ResultSet resultSet, String field) throws SQLException {
        return ManagedCoopReadValidation.requireText(resultSet.getString(field), field);
    }

    private String requiredRaw(ResultSet resultSet, String field) throws SQLException {
        String value = resultSet.getString(field);
        if (value == null || value.isBlank()) {
            throw integrity("missing_managed_coop_field:" + field);
        }
        return value;
    }

    private boolean strictBoolean(ResultSet resultSet, String field) throws SQLException {
        return ManagedCoopReadValidation.strictBoolean(resultSet.getInt(field), field);
    }

    @Nullable
    private String optional(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String field)
            throws ManagedCoopIntegrityException {
        E parsed = optionalEnum(type, value, field);
        if (parsed == null) {
            throw integrity("missing_managed_coop_field:" + field);
        }
        return parsed;
    }

    @Nullable
    private <E extends Enum<E>> E optionalEnum(Class<E> type,
                                                @Nullable String value,
                                                String field) throws ManagedCoopIntegrityException {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw integrity("unknown_managed_coop_" + field + ":" + value, exception);
        }
    }

    private ManagedCoopIntegrityException integrity(String reason) {
        return new ManagedCoopIntegrityException(reason);
    }

    private ManagedCoopIntegrityException integrity(String reason, Throwable cause) {
        return new ManagedCoopIntegrityException(reason, cause);
    }
}

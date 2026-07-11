package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.BeginSessionRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.FinalizationRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationResult;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationStatus;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionEnvelope;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;

/** Owns import session creation, authority transitions, and terminal publication. */
final class ManagedCoopImportSessionTransactions {
    private final ManagedCoopImportReader reader = new ManagedCoopImportReader();
    private final ManagedCoopImportSourceTransactions sourceTransactions;

    ManagedCoopImportSessionTransactions(ManagedCoopImportSourceTransactions sourceTransactions) {
        this.sourceTransactions = sourceTransactions;
    }

    MutationResult begin(Connection connection, BeginSessionRequest request) throws SQLException {
        SessionRecord existing = reader.loadById(connection, request.envelope().sessionId());
        if (existing != null) {
            return replayBegin(connection, request, existing);
        }
        SessionRecord active = loadActiveForAuthority(
                connection, request.envelope().authorityKey().authorityId());
        if (active != null) {
            return result(MutationStatus.CONFLICT, active,
                    "different_active_import_session");
        }
        AuthorityRow authority = loadAuthority(connection, request.envelope().authorityKey());
        if (authority == null) {
            return result(MutationStatus.NOT_FOUND, null, "authority_not_found");
        }
        if (!authority.matches(request.envelope(), AuthorityState.VANILLA_DISCOVERED)) {
            return result(MutationStatus.CONFLICT, null,
                    "authority_identity_or_state_mismatch");
        }
        insertSession(connection, request);
        insertSources(connection, request);
        transitionToImporting(connection, request.envelope());
        SessionRecord inserted = reader.loadById(connection, request.envelope().sessionId());
        List<SourceRecord> sources = reader.loadSources(connection, request.envelope().sessionId());
        if (inserted == null || !sameBeginEvidence(request, inserted, sources)) {
            throw integrity("managed_coop_import_begin_verification_failed");
        }
        return result(MutationStatus.APPLIED, inserted, null);
    }

    MutationResult finalizeAuthority(Connection connection, FinalizationRequest request)
            throws SQLException {
        SessionRecord session = reader.loadById(connection, request.sessionId());
        if (session == null) {
            return result(MutationStatus.NOT_FOUND, null, "import_session_not_found");
        }
        if (!sessionIdentityMatches(session, request)) {
            return result(MutationStatus.CONFLICT, session,
                    "import_session_finalization_identity_mismatch");
        }
        if (!session.active()) {
            return replayFinalization(session, request);
        }
        AuthorityRow authority = loadAuthority(connection, request.authorityKey());
        if (authority == null || !authority.matches(
                session.envelope(), AuthorityState.IMPORTING_TO_TWORK)) {
            return result(MutationStatus.CONFLICT, session,
                    "authority_not_importing_for_session");
        }
        TerminalSummary terminal = terminalSummary(connection, session);
        if (!terminal.complete()) {
            return result(MutationStatus.INVARIANT_BLOCKED, session, terminal.detail());
        }
        AuthorityState derivedTarget = terminal.quarantined() > 0
                ? AuthorityState.CONFLICT : AuthorityState.TWORK_MANAGED;
        if (derivedTarget == AuthorityState.TWORK_MANAGED
                && hasUnresolvedAuthorityConflict(connection, session)) {
            return result(MutationStatus.INVARIANT_BLOCKED, session,
                    "unresolved_authority_import_conflict");
        }
        if (request.targetState() != derivedTarget) {
            return result(MutationStatus.CONFLICT, session,
                    "final_authority_state_does_not_match_source_dispositions");
        }
        String referenceFailure = sourceTransactions.validateAllTerminalBindings(
                connection, session);
        if (referenceFailure != null) {
            return result(MutationStatus.INVARIANT_BLOCKED, session, referenceFailure);
        }
        publishFinalAuthority(connection, request, derivedTarget);
        finalizeSession(connection, request, derivedTarget);
        SessionRecord finalized = reader.loadById(connection, request.sessionId());
        if (finalized == null || finalized.active()
                || !request.commandId().equals(finalized.finalCommandId())) {
            throw integrity("managed_coop_import_finalization_verification_failed");
        }
        return result(MutationStatus.APPLIED, finalized, null);
    }

    private MutationResult replayBegin(Connection connection,
                                       BeginSessionRequest request,
                                       SessionRecord existing) throws SQLException {
        List<SourceRecord> sources = reader.loadSources(connection, existing.envelope().sessionId());
        if (!sameBeginEvidence(request, existing, sources)) {
            return result(MutationStatus.CONFLICT, existing,
                    "import_session_id_evidence_mismatch");
        }
        return result(MutationStatus.IDEMPOTENT, existing, null);
    }

    private MutationResult replayFinalization(SessionRecord session, FinalizationRequest request) {
        SessionState expected = request.targetState() == AuthorityState.CONFLICT
                ? SessionState.FINALIZED_CONFLICT : SessionState.FINALIZED_MANAGED;
        boolean same = session.state() == expected
                && request.commandId().equals(session.finalCommandId());
        return result(same ? MutationStatus.IDEMPOTENT : MutationStatus.CONFLICT,
                session, same ? null : "import_session_finalized_differently");
    }

    private TerminalSummary terminalSummary(Connection connection, SessionRecord session)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN disposition_kind IN ('MATCHED', 'IMPORTED')
                                    AND neutralization_state = 'VERIFIED_ABSENT' THEN 1
                                WHEN disposition_kind = 'QUARANTINED'
                                    AND neutralization_state = 'NOT_REQUIRED' THEN 1 ELSE 0 END) AS terminal,
                       SUM(CASE WHEN disposition_kind = 'QUARANTINED' THEN 1 ELSE 0 END) AS quarantined
                FROM managed_coop_import_sources WHERE session_id = ?
                """)) {
            statement.setString(1, session.envelope().sessionId());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                int total = resultSet.getInt("total");
                int terminal = resultSet.getInt("terminal");
                int quarantined = resultSet.getInt("quarantined");
                boolean complete = total == session.sourceCount() && terminal == total;
                String detail = total != session.sourceCount()
                        ? "import_source_count_mismatch" : "import_sources_not_terminal";
                return new TerminalSummary(complete, quarantined, complete ? null : detail);
            }
        }
    }

    private boolean hasUnresolvedAuthorityConflict(Connection connection, SessionRecord session)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM coop_import_conflicts
                WHERE authority_id = ? AND resolution_state = 'UNRESOLVED' LIMIT 1
                """)) {
            statement.setString(1, session.envelope().authorityKey().authorityId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void insertSession(Connection connection, BeginSessionRequest request)
            throws SQLException {
        SessionEnvelope envelope = request.envelope();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_import_sessions (
                    session_id, authority_id, world_name, coop_id, x, y, z,
                    audit_version, audit_fingerprint, audit_envelope_json, audit_envelope_hash,
                    layout_id, coop_asset_id, resident_list_class_name, produce_payload,
                    produce_fingerprint, source_count, state, active, begin_command_id,
                    created_at_ms, updated_at_ms, finalized_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, 0)
                """)) {
            int index = bindSessionEnvelope(statement, envelope);
            statement.setInt(index++, request.sources().size());
            statement.setString(index++, envelope.beginCommandId());
            statement.setLong(index++, envelope.createdAtMs());
            statement.setLong(index, envelope.createdAtMs());
            requireOne(statement.executeUpdate(), "managed_coop_import_session_insert_count");
        }
    }

    private int bindSessionEnvelope(PreparedStatement statement, SessionEnvelope envelope)
            throws SQLException {
        int index = 1;
        statement.setString(index++, envelope.sessionId());
        statement.setString(index++, envelope.authorityKey().authorityId());
        statement.setString(index++, envelope.authorityKey().worldName());
        statement.setString(index++, envelope.coopId());
        statement.setInt(index++, envelope.authorityKey().x());
        statement.setInt(index++, envelope.authorityKey().y());
        statement.setInt(index++, envelope.authorityKey().z());
        statement.setInt(index++, envelope.auditVersion());
        statement.setString(index++, envelope.auditFingerprint());
        statement.setString(index++, envelope.auditEnvelopeJson());
        statement.setString(index++, envelope.auditEnvelopeHash());
        statement.setString(index++, envelope.layoutId());
        statement.setString(index++, envelope.coopAssetId());
        statement.setString(index++, envelope.residentListClassName());
        statement.setString(index++, envelope.producePayload());
        statement.setString(index++, envelope.produceFingerprint());
        return index;
    }

    private void insertSources(Connection connection, BeginSessionRequest request)
            throws SQLException {
        for (SourceEvidence source : request.sources()) {
            insertSource(connection, request.envelope(), source);
        }
    }

    private void insertSource(Connection connection, SessionEnvelope envelope, SourceEvidence source)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_import_sources (
                    source_id, session_id, source_fingerprint, source_envelope_json,
                    source_envelope_hash, source_payload, source_payload_hash,
                    locator_hints_json, locator_hints_hash, source_slot, source_order,
                    metadata_present, persistent_ref_present, persistent_uuid, deployed_to_world,
                    last_produced, profile_at_audit_id, role_id, display_name,
                    managed_snapshot_json, managed_snapshot_hash, managed_snapshot_version,
                    unavailable_fields_json, neutralization_state, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'NOT_AUTHORIZED', ?)
                """)) {
            bindSource(statement, envelope, source);
            requireOne(statement.executeUpdate(), "managed_coop_import_source_insert_count");
        }
    }

    private void bindSource(PreparedStatement statement,
                            SessionEnvelope envelope,
                            SourceEvidence source) throws SQLException {
        int index = 1;
        statement.setString(index++, source.sourceId());
        statement.setString(index++, envelope.sessionId());
        statement.setString(index++, source.sourceFingerprint());
        statement.setString(index++, source.sourceEnvelopeJson());
        statement.setString(index++, source.sourceEnvelopeHash());
        statement.setString(index++, source.sourcePayload());
        statement.setString(index++, source.sourcePayloadHash());
        statement.setString(index++, source.locatorHintsJson());
        statement.setString(index++, source.locatorHintsHash());
        statement.setInt(index++, source.sourceSlot());
        statement.setInt(index++, source.sourceOrder());
        statement.setInt(index++, source.metadataPresent() ? 1 : 0);
        statement.setInt(index++, source.persistentRefPresent() ? 1 : 0);
        statement.setString(index++, source.persistentUuid() == null
                ? null : source.persistentUuid().toString());
        statement.setInt(index++, source.deployedToWorld() ? 1 : 0);
        statement.setString(index++, source.lastProduced());
        statement.setString(index++, source.profileAtAuditId());
        statement.setString(index++, source.roleId());
        statement.setString(index++, source.displayName());
        statement.setString(index++, source.managedSnapshotJson());
        statement.setString(index++, source.managedSnapshotHash());
        statement.setInt(index++, source.managedSnapshotVersion());
        statement.setString(index++, source.unavailableFieldsJson());
        statement.setLong(index, envelope.createdAtMs());
    }

    private void transitionToImporting(Connection connection, SessionEnvelope envelope)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_coop_authority
                SET authority_state = 'IMPORTING_TO_TWORK', updated_at_ms = ?, last_error = NULL
                WHERE authority_id = ? AND lower(coop_id) = lower(?)
                  AND authority_state = 'VANILLA_DISCOVERED' AND active = 1
                """)) {
            statement.setLong(1, envelope.createdAtMs());
            statement.setString(2, envelope.authorityKey().authorityId());
            statement.setString(3, envelope.coopId());
            requireOne(statement.executeUpdate(), "managed_coop_import_authority_transition_count");
        }
    }

    private void publishFinalAuthority(Connection connection,
                                       FinalizationRequest request,
                                       AuthorityState target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_coop_authority
                SET authority_state = ?, import_version = import_version + 1,
                    updated_at_ms = ?, last_error = ?
                WHERE authority_id = ? AND lower(coop_id) = lower(?)
                  AND authority_state = 'IMPORTING_TO_TWORK' AND active = 1
                """)) {
            statement.setString(1, target.name());
            statement.setLong(2, request.finalizedAtMs());
            statement.setString(3, target == AuthorityState.CONFLICT
                    ? "managed_coop_import_quarantined_source" : null);
            statement.setString(4, request.authorityKey().authorityId());
            statement.setString(5, request.coopId());
            requireOne(statement.executeUpdate(), "managed_coop_import_authority_finalize_count");
        }
    }

    private void finalizeSession(Connection connection,
                                 FinalizationRequest request,
                                 AuthorityState target) throws SQLException {
        SessionState state = target == AuthorityState.CONFLICT
                ? SessionState.FINALIZED_CONFLICT : SessionState.FINALIZED_MANAGED;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_coop_import_sessions
                SET state = ?, active = 0, final_command_id = ?, updated_at_ms = ?,
                    finalized_at_ms = ?, last_error = ?
                WHERE session_id = ? AND state = 'ACTIVE' AND active = 1
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, request.commandId());
            statement.setLong(3, request.finalizedAtMs());
            statement.setLong(4, request.finalizedAtMs());
            statement.setString(5, target == AuthorityState.CONFLICT
                    ? "quarantined_import_source" : null);
            statement.setString(6, request.sessionId());
            requireOne(statement.executeUpdate(), "managed_coop_import_session_finalize_count");
        }
    }

    private SessionRecord loadActiveForAuthority(Connection connection, String authorityId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT session_id FROM managed_coop_import_sessions "
                        + "WHERE authority_id = ? AND active = 1 LIMIT 2")) {
            statement.setString(1, authorityId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String sessionId = resultSet.getString(1);
                if (resultSet.next()) {
                    throw integrity("duplicate_active_managed_coop_import_authority");
                }
                return reader.loadById(connection, sessionId);
            }
        }
    }

    private AuthorityRow loadAuthority(Connection connection, ManagedCoopAuthorityKey key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT authority_id, world_name, coop_id, x, y, z, authority_state, active
                FROM managed_coop_authority WHERE authority_id = ? LIMIT 2
                """)) {
            statement.setString(1, key.authorityId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                AuthorityRow row = mapAuthority(resultSet);
                if (resultSet.next()) {
                    throw integrity("duplicate_managed_coop_authority_id");
                }
                return row;
            }
        }
    }

    private AuthorityRow mapAuthority(ResultSet resultSet) throws SQLException {
        try {
            return new AuthorityRow(
                    resultSet.getString("authority_id"), resultSet.getString("world_name"),
                    resultSet.getString("coop_id"), resultSet.getInt("x"),
                    resultSet.getInt("y"), resultSet.getInt("z"),
                    AuthorityState.valueOf(resultSet.getString("authority_state")),
                    resultSet.getInt("active") == 1);
        } catch (IllegalArgumentException exception) {
            throw new ManagedCoopIntegrityException(
                    "invalid_managed_coop_import_authority_row", exception);
        }
    }

    private boolean sameBeginEvidence(BeginSessionRequest request,
                                      SessionRecord record,
                                      List<SourceRecord> sources) {
        if (!request.envelope().equals(record.envelope())
                || record.sourceCount() != request.sources().size()
                || sources.size() != request.sources().size()) {
            return false;
        }
        for (int index = 0; index < sources.size(); index++) {
            SourceRecord persisted = sources.get(index);
            if (!persisted.evidence().equals(request.sources().get(index))
                    || persisted.createdAtMs() != request.envelope().createdAtMs()) {
                return false;
            }
        }
        return true;
    }

    private boolean sessionIdentityMatches(SessionRecord session, FinalizationRequest request) {
        return session.envelope().authorityKey().equals(request.authorityKey())
                && session.envelope().coopId().equalsIgnoreCase(request.coopId())
                && session.envelope().auditFingerprint().equals(request.auditFingerprint());
    }

    private void requireOne(int count, String reason) throws ManagedCoopIntegrityException {
        if (count != 1) {
            throw integrity(reason);
        }
    }

    private MutationResult result(MutationStatus status, SessionRecord session, String detail) {
        return new MutationResult(status, session, null, detail);
    }

    private ManagedCoopIntegrityException integrity(String reason) {
        return new ManagedCoopIntegrityException(reason);
    }

    private record AuthorityRow(String authorityId,
                                String worldName,
                                String coopId,
                                int x,
                                int y,
                                int z,
                                AuthorityState state,
                                boolean active) {
        boolean matches(SessionEnvelope envelope, AuthorityState expectedState) {
            ManagedCoopAuthorityKey key = envelope.authorityKey();
            return active && state == expectedState && authorityId.equals(key.authorityId())
                    && worldName.equalsIgnoreCase(key.worldName())
                    && coopId.equalsIgnoreCase(envelope.coopId())
                    && x == key.x() && y == key.y() && z == key.z();
        }
    }

    private record TerminalSummary(boolean complete, int quarantined, String detail) {
    }
}

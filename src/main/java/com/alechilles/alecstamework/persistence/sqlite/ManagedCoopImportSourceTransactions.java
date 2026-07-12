package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationResult;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.MutationStatus;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationProof;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.NeutralizationState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceRecord;

/** Owns exact source disposition binding and absence-proof transactions. */
final class ManagedCoopImportSourceTransactions {
    private final ManagedCoopImportReader reader = new ManagedCoopImportReader();
    private final ManagedCoopImportBindingValidator bindingValidator =
            new ManagedCoopImportBindingValidator();

    MutationResult preflightDisposition(Connection connection, DispositionBinding binding)
            throws SQLException {
        SessionRecord session = reader.loadById(connection, binding.sessionId());
        SourceRecord source = reader.loadSource(connection, binding.sourceId());
        MutationResult gate = bindingGate(session, source, binding);
        if (gate != null) {
            return gate;
        }
        if (source.disposition() == null) {
            return null;
        }
        return sameBinding(source, binding)
                ? result(MutationStatus.IDEMPOTENT, session, source, null)
                : result(MutationStatus.CONFLICT, session, source,
                        "source_disposition_already_bound_differently");
    }

    MutationResult bindDisposition(Connection connection, DispositionBinding binding)
            throws SQLException {
        MutationResult preflight = preflightDisposition(connection, binding);
        if (preflight != null) {
            return preflight;
        }
        SessionRecord session = reader.loadById(connection, binding.sessionId());
        SourceRecord source = reader.loadSource(connection, binding.sourceId());
        String referenceFailure = bindingValidator.validate(
                connection, session, source, binding, false);
        if (referenceFailure != null) {
            return result(MutationStatus.INVARIANT_BLOCKED, session, source, referenceFailure);
        }
        updateDisposition(connection, binding);
        SourceRecord bound = reader.loadSource(connection, binding.sourceId());
        if (bound == null || !sameBinding(bound, binding)) {
            throw integrity("managed_coop_import_disposition_verification_failed");
        }
        return result(MutationStatus.APPLIED, session, bound, null);
    }

    MutationResult recordNeutralization(Connection connection, NeutralizationProof proof)
            throws SQLException {
        SessionRecord session = reader.loadById(connection, proof.sessionId());
        SourceRecord source = reader.loadSource(connection, proof.sourceId());
        MutationResult gate = neutralizationGate(session, source, proof);
        if (gate != null) {
            return gate;
        }
        if (source.neutralizationState() == NeutralizationState.VERIFIED_ABSENT) {
            return sameProof(source, proof)
                    ? result(MutationStatus.IDEMPOTENT, session, source, null)
                    : result(MutationStatus.CONFLICT, session, source,
                            "source_absence_proof_already_recorded_differently");
        }
        if (source.neutralizationState() != NeutralizationState.AUTHORIZED) {
            return result(MutationStatus.CONFLICT, session, source,
                    "source_neutralization_not_authorized");
        }
        if (!operationReadyForNeutralization(connection, source.operationId())) {
            return result(MutationStatus.INVARIANT_BLOCKED, session, source,
                    "import_operation_not_ready_for_neutralization");
        }
        completeImportOperation(connection, source.operationId(), proof.verifiedAtMs());
        updateNeutralization(connection, proof);
        SourceRecord verified = reader.loadSource(connection, proof.sourceId());
        if (verified == null || !sameProof(verified, proof)) {
            throw integrity("managed_coop_import_neutralization_verification_failed");
        }
        return result(MutationStatus.APPLIED, session, verified, null);
    }

    MutationResult refreshNeutralization(Connection connection, NeutralizationProof proof)
            throws SQLException {
        SessionRecord session = reader.loadById(connection, proof.sessionId());
        SourceRecord source = reader.loadSource(connection, proof.sourceId());
        MutationResult gate = neutralizationGate(session, source, proof);
        if (gate != null) {
            return gate;
        }
        if (source.neutralizationState() != NeutralizationState.VERIFIED_ABSENT) {
            return result(MutationStatus.CONFLICT, session, source,
                    "source_absence_proof_not_yet_recorded");
        }
        if (sameProof(source, proof)) {
            return result(MutationStatus.IDEMPOTENT, session, source, null);
        }
        if (proof.verifiedAtMs() != source.verifiedAbsentAtMs()
                || !operationCompletedForNeutralization(connection, source.operationId())) {
            return result(MutationStatus.INVARIANT_BLOCKED, session, source,
                    "completed_import_operation_required_for_absence_revalidation");
        }
        refreshNeutralizationProof(connection, source, proof);
        SourceRecord refreshed = reader.loadSource(connection, proof.sourceId());
        if (refreshed == null || !sameProof(refreshed, proof)) {
            throw integrity("managed_coop_import_neutralization_refresh_failed");
        }
        return result(MutationStatus.APPLIED, session, refreshed, null);
    }

    String validateAllTerminalBindings(Connection connection, SessionRecord session)
            throws SQLException {
        for (SourceRecord source : reader.loadSources(connection, session.envelope().sessionId())) {
            DispositionBinding binding = bindingFrom(
                    source, session.envelope().auditFingerprint());
            String failure = bindingValidator.validate(connection, session, source, binding, true);
            if (failure != null) {
                return failure + ":" + source.evidence().sourceId();
            }
        }
        return null;
    }

    private MutationResult bindingGate(SessionRecord session,
                                       SourceRecord source,
                                       DispositionBinding binding) {
        if (session == null || source == null) {
            return result(MutationStatus.NOT_FOUND, session, source,
                    session == null ? "import_session_not_found" : "import_source_not_found");
        }
        if (!session.active() || session.state() != SessionState.ACTIVE) {
            return result(MutationStatus.CONFLICT, session, source, "import_session_not_active");
        }
        if (!source.sessionId().equals(binding.sessionId())
                || !session.envelope().auditFingerprint().equals(binding.auditFingerprint())
                || !source.evidence().sourceFingerprint().equals(binding.sourceFingerprint())) {
            return result(MutationStatus.CONFLICT, session, source,
                    "import_source_binding_identity_mismatch");
        }
        return null;
    }

    private MutationResult neutralizationGate(SessionRecord session,
                                              SourceRecord source,
                                              NeutralizationProof proof) {
        if (session == null || source == null) {
            return result(MutationStatus.NOT_FOUND, session, source,
                    session == null ? "import_session_not_found" : "import_source_not_found");
        }
        SourceEvidence evidence = source.evidence();
        boolean exact = session.active() && source.sessionId().equals(proof.sessionId())
                && session.envelope().auditFingerprint().equals(proof.auditFingerprint())
                && evidence.sourceFingerprint().equals(proof.sourceFingerprint())
                && evidence.sourcePayloadHash().equals(proof.sourcePayloadHash())
                && evidence.sourceSlot() == proof.sourceSlot()
                && evidence.sourceOrder() == proof.sourceOrder()
                && Objects.equals(evidence.persistentUuid(), proof.persistentUuid())
                && proof.commandId().equals(source.dispositionCommandId());
        if (!exact) {
            return result(MutationStatus.CONFLICT, session, source,
                    "neutralization_proof_source_identity_mismatch");
        }
        if (source.disposition() == DispositionKind.QUARANTINED) {
            return result(MutationStatus.CONFLICT, session, source,
                    "quarantined_source_must_remain_untouched");
        }
        return null;
    }

    private DispositionBinding bindingFrom(SourceRecord source, String auditFingerprint) {
        return new DispositionBinding(
                source.sessionId(), source.evidence().sourceId(), auditFingerprint,
                source.evidence().sourceFingerprint(), source.dispositionCommandId(),
                source.disposition(), source.operationId(), source.residentId(),
                source.profileId(), source.conflictId(), source.conflictKind(),
                source.dispositionAtMs());
    }

    private void updateDisposition(Connection connection, DispositionBinding binding)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_coop_import_sources
                SET disposition_kind = ?, disposition_command_id = ?, operation_id = ?,
                    resident_id = ?, profile_id = ?, conflict_id = ?, conflict_kind = ?,
                    neutralization_state = ?, disposition_at_ms = ?
                WHERE source_id = ? AND session_id = ? AND disposition_kind IS NULL
                """)) {
            statement.setString(1, binding.disposition().name());
            statement.setString(2, binding.commandId());
            statement.setString(3, binding.operationId());
            statement.setString(4, binding.residentId());
            statement.setString(5, binding.profileId());
            statement.setString(6, binding.conflictId());
            statement.setString(7, binding.conflictKind());
            statement.setString(8, binding.disposition() == DispositionKind.QUARANTINED
                    ? NeutralizationState.NOT_REQUIRED.name()
                    : NeutralizationState.AUTHORIZED.name());
            statement.setLong(9, binding.boundAtMs());
            statement.setString(10, binding.sourceId());
            statement.setString(11, binding.sessionId());
            requireOne(statement.executeUpdate(), "managed_coop_import_disposition_update_count");
        }
    }

    private void updateNeutralization(Connection connection, NeutralizationProof proof)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_coop_import_sources
                SET neutralization_state = 'VERIFIED_ABSENT', neutralization_command_id = ?,
                    absence_proof_json = ?, absence_proof_hash = ?, absence_proof_version = ?,
                    verified_absent_at_ms = ?
                WHERE source_id = ? AND session_id = ? AND neutralization_state = 'AUTHORIZED'
                """)) {
            statement.setString(1, proof.commandId());
            statement.setString(2, proof.absenceProofJson());
            statement.setString(3, proof.absenceProofHash());
            statement.setInt(4, proof.absenceProofVersion());
            statement.setLong(5, proof.verifiedAtMs());
            statement.setString(6, proof.sourceId());
            statement.setString(7, proof.sessionId());
            requireOne(statement.executeUpdate(), "managed_coop_import_neutralization_update_count");
        }
    }

    private void refreshNeutralizationProof(Connection connection,
                                            SourceRecord source,
                                            NeutralizationProof proof) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_coop_import_sources
                SET absence_proof_json = ?, absence_proof_hash = ?, absence_proof_version = ?,
                    verified_absent_at_ms = ?
                WHERE source_id = ? AND session_id = ?
                  AND neutralization_state = 'VERIFIED_ABSENT'
                  AND neutralization_command_id = ?
                """)) {
            statement.setString(1, proof.absenceProofJson());
            statement.setString(2, proof.absenceProofHash());
            statement.setInt(3, proof.absenceProofVersion());
            statement.setLong(4, proof.verifiedAtMs());
            statement.setString(5, proof.sourceId());
            statement.setString(6, proof.sessionId());
            statement.setString(7, source.neutralizationCommandId());
            requireOne(statement.executeUpdate(),
                    "managed_coop_import_neutralization_refresh_count");
        }
    }

    private boolean operationReadyForNeutralization(Connection connection, String operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state, generation, active, completed_at_ms
                FROM coop_lifecycle_operations WHERE operation_id = ? LIMIT 2
                """)) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && "SOURCE_RETIRE_REQUESTED".equals(resultSet.getString("state"))
                        && resultSet.getLong("generation") == 2L
                        && resultSet.getInt("active") == 1
                        && resultSet.getLong("completed_at_ms") == 0L
                        && !resultSet.next();
            }
        }
    }

    private boolean operationCompletedForNeutralization(Connection connection,
                                                         String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_kind, state, generation, active, completed_at_ms
                FROM coop_lifecycle_operations WHERE operation_id = ? LIMIT 2
                """)) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && "IMPORT".equals(resultSet.getString("operation_kind"))
                        && "COMPLETE".equals(resultSet.getString("state"))
                        && resultSet.getLong("generation") == 3L
                        && resultSet.getInt("active") == 0
                        && resultSet.getLong("completed_at_ms") != 0L
                        && !resultSet.next();
            }
        }
    }

    private void completeImportOperation(Connection connection,
                                         String operationId,
                                         long completedAtMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coop_lifecycle_operations
                SET state = 'COMPLETE', generation = 3, active = 0,
                    updated_at_ms = ?, completed_at_ms = ?, last_error = NULL
                WHERE operation_id = ? AND operation_kind = 'IMPORT'
                  AND state = 'SOURCE_RETIRE_REQUESTED' AND generation = 2
                  AND active = 1 AND completed_at_ms = 0
                """)) {
            statement.setLong(1, completedAtMs);
            statement.setLong(2, completedAtMs);
            statement.setString(3, operationId);
            requireOne(statement.executeUpdate(), "managed_coop_import_operation_complete_count");
        }
    }

    private boolean sameBinding(SourceRecord source, DispositionBinding binding) {
        return source.disposition() == binding.disposition()
                && binding.commandId().equals(source.dispositionCommandId())
                && Objects.equals(binding.operationId(), source.operationId())
                && Objects.equals(binding.residentId(), source.residentId())
                && Objects.equals(binding.profileId(), source.profileId())
                && Objects.equals(binding.conflictId(), source.conflictId())
                && Objects.equals(binding.conflictKind(), source.conflictKind());
    }

    private boolean sameProof(SourceRecord source, NeutralizationProof proof) {
        return source.neutralizationState() == NeutralizationState.VERIFIED_ABSENT
                && proof.commandId().equals(source.neutralizationCommandId())
                && proof.absenceProofJson().equals(source.absenceProofJson())
                && proof.absenceProofHash().equals(source.absenceProofHash())
                && proof.absenceProofVersion() == source.absenceProofVersion()
                && proof.verifiedAtMs() == source.verifiedAbsentAtMs();
    }

    private void requireOne(int count, String reason) throws ManagedCoopIntegrityException {
        if (count != 1) {
            throw integrity(reason);
        }
    }

    private MutationResult result(MutationStatus status,
                                  SessionRecord session,
                                  SourceRecord source,
                                  String detail) {
        return new MutationResult(status, session, source, detail);
    }

    private ManagedCoopIntegrityException integrity(String reason) {
        return new ManagedCoopIntegrityException(reason);
    }
}

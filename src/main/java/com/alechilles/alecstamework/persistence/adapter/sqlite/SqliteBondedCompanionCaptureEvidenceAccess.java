package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEvidence;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns exact durable capture-completion evidence reads and checkpoints. */
final class SqliteBondedCompanionCaptureEvidenceAccess {
    private final SqliteConnectionFactory connections;
    private final SqliteBondedCompanionOperationExecutor operations;

    SqliteBondedCompanionCaptureEvidenceAccess(
            SqliteConnectionFactory connections,
            SqliteBondedCompanionOperationExecutor operations
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    Optional<BondedCompanionCaptureEvidence> find(
            UUID ownerUuid,
            String rosterId,
            UUID sourceNpcUuid
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT result_json
                     FROM bonded_companion_operation
                     WHERE owner_uuid = ? AND roster_id = ?
                       AND operation_type = 'CAPTURE'
                       AND operation_state = 'SUCCEEDED'
                       AND json_extract(
                           result_json,
                           '$.captureEvidence.sourceNpcUuid'
                       ) = ?
                     ORDER BY created_at_ms DESC, caller_namespace,
                              idempotency_key
                     LIMIT 1
                     """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, text(rosterId, "rosterId"));
            statement.setString(3, sourceNpcUuid.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(operations.captureEvidence(
                                row.getString(1)))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "bonded-capture-evidence-read-failed", failure);
        }
    }

    /** Internal cross-owner fence preventing one lingering source being recaptured. */
    List<BondedCompanionCaptureEvidence> findBySource(
            UUID sourceNpcUuid,
            int limit
    ) {
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT result_json
                     FROM bonded_companion_operation
                     WHERE operation_type = 'CAPTURE'
                       AND operation_state = 'SUCCEEDED'
                       AND json_type(
                           result_json, '$.captureEvidence'
                       ) = 'object'
                       AND json_extract(
                           result_json,
                           '$.captureEvidence.sourceNpcUuid'
                       ) = ?
                     ORDER BY created_at_ms, caller_namespace, idempotency_key
                     LIMIT ?
                     """)) {
            statement.setString(1, sourceNpcUuid.toString());
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<BondedCompanionCaptureEvidence> result =
                        new ArrayList<>();
                while (rows.next()) {
                    result.add(operations.captureEvidence(rows.getString(1)));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "bonded-capture-evidence-read-failed", failure);
        }
    }

    List<BondedCompanionCaptureEvidence> listUnpublished(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT result_json
                     FROM bonded_companion_operation
                     WHERE operation_type = 'CAPTURE'
                       AND operation_state = 'SUCCEEDED'
                       AND json_type(
                           result_json, '$.captureEvidence'
                       ) = 'object'
                       AND json_type(
                           result_json, '$.captureEventPublishedAtMs'
                       ) IS NULL
                     ORDER BY created_at_ms, caller_namespace, idempotency_key
                     LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<BondedCompanionCaptureEvidence> result =
                        new ArrayList<>();
                while (rows.next()) {
                    result.add(operations.captureEvidence(rows.getString(1)));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "bonded-capture-evidence-read-failed", failure);
        }
    }

    boolean markPublished(
            BondedCompanionCaptureEvidence evidence,
            long publishedAtMs
    ) {
        Objects.requireNonNull(evidence, "evidence");
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE bonded_companion_operation
                     SET result_json = json_set(
                             result_json,
                             '$.captureEventPublishedAtMs', ?
                         ),
                         updated_at_ms = ?
                     WHERE caller_namespace = ? AND idempotency_key = ?
                       AND operation_state = 'SUCCEEDED'
                       AND json_extract(
                           result_json, '$.captureEvidence.operationId'
                       ) = ?
                       AND json_type(
                           result_json, '$.captureEventPublishedAtMs'
                       ) IS NULL
                     """)) {
            statement.setLong(1, publishedAtMs);
            statement.setLong(2, publishedAtMs);
            statement.setString(3, evidence.callerNamespace());
            statement.setString(4, evidence.idempotencyKey());
            statement.setString(5, evidence.operationId().toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "bonded-capture-evidence-checkpoint-failed", failure);
        }
    }

    void requireMatches(
            BondedCompanionOperation operation,
            BondedCompanionRecord.Profile profile,
            BondedCompanionCaptureEvidence evidence
    ) {
        if (operation.type() != BondedCompanionOperation.Type.CAPTURE
                || !operation.callerNamespace().equals(
                        evidence.callerNamespace())
                || !operation.idempotencyKey().equals(
                        evidence.idempotencyKey())
                || !profile.ownerUuid().equals(evidence.ownerUuid())
                || !profile.rosterId().equals(evidence.rosterId())
                || !profile.familyId().equals(evidence.familyId())
                || !profile.profileId().equals(evidence.profileId())
                || !profile.roleId().equals(evidence.roleId())) {
            throw new IllegalArgumentException(
                    "capture evidence does not match operation/profile");
        }
    }

    private String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}

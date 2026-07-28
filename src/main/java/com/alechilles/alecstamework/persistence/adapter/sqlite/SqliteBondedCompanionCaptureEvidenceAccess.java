package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEvidence;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns profile-lifetime capture-source reads and publication checkpoints. */
final class SqliteBondedCompanionCaptureEvidenceAccess {
    private final SqliteConnectionFactory connections;

    SqliteBondedCompanionCaptureEvidenceAccess(
            SqliteConnectionFactory connections
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    Optional<BondedCompanionCaptureEvidence> find(
            UUID ownerUuid,
            String rosterId,
            UUID sourceNpcUuid
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteBondedCompanionCaptureSourceStore(connection)
                    .find(ownerUuid, text(rosterId, "rosterId"), sourceNpcUuid)
                    .map(SqliteBondedCompanionCaptureSourceRow::evidence);
        } catch (SQLException failure) {
            throw readFailure(failure);
        }
    }

    List<SqliteBondedCompanionCaptureSourceRow> findRowsBySource(
            UUID sourceNpcUuid,
            int limit
    ) {
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        positive(limit);
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteBondedCompanionCaptureSourceStore(connection)
                    .findBySource(sourceNpcUuid, limit);
        } catch (SQLException failure) {
            throw readFailure(failure);
        }
    }

    List<BondedCompanionCaptureEvidence> findBySource(
            UUID sourceNpcUuid,
            int limit
    ) {
        return findRowsBySource(sourceNpcUuid, limit).stream()
                .map(SqliteBondedCompanionCaptureSourceRow::evidence)
                .toList();
    }

    List<BondedCompanionCaptureEvidence> listUnpublished(int limit) {
        positive(limit);
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteBondedCompanionCaptureSourceStore(connection)
                    .unpublished(limit).stream()
                    .map(SqliteBondedCompanionCaptureSourceRow::evidence)
                    .toList();
        } catch (SQLException failure) {
            throw readFailure(failure);
        }
    }

    boolean markPublished(
            BondedCompanionCaptureEvidence evidence,
            long publishedAtMs
    ) {
        Objects.requireNonNull(evidence, "evidence");
        try (Connection connection = connections.openWriterConnection()) {
            return new SqliteBondedCompanionCaptureSourceStore(connection)
                    .markPublished(evidence, publishedAtMs);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "bonded-capture-evidence-checkpoint-failed", failure);
        }
    }

    void requireMatches(
            BondedCompanionOperation operation,
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Cleanup cleanup,
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
                || !profile.roleId().equals(evidence.roleId())
                || cleanup.target() != BondedCompanionRecord.CleanupTarget.SOURCE
                || !cleanup.targetNpcUuid().equals(evidence.sourceNpcUuid())
                || !cleanup.worldKey().equals(evidence.sourceWorldKey())
                || !cleanup.ownerUuid().equals(evidence.ownerUuid())
                || !cleanup.rosterId().equals(evidence.rosterId())
                || !cleanup.profileId().equals(evidence.profileId())) {
            throw new IllegalArgumentException(
                    "capture evidence does not match operation/profile/cleanup");
        }
    }

    private IllegalStateException readFailure(SQLException failure) {
        return new IllegalStateException(
                "bonded-capture-evidence-read-failed", failure);
    }

    private void positive(int limit) {
        if (limit <= 0) throw new IllegalArgumentException(
                "limit must be positive");
    }

    private String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(
                field + " is required");
        return normalized;
    }
}

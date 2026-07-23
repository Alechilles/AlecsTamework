package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationLeaseRequest;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.operation.OperationStore;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
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
 * Connection-bound SQLite store for the shared replacement operation protocol.
 *
 * <p>Preparation, participants, phase evidence, and leases remain inside the caller's one
 * operation transaction. No feature-specific phase vocabulary is permitted here.</p>
 */
public final class SqliteOperationStore implements OperationStore {
    private static final String SELECT_COLUMNS = """
            operation_id, idempotency_key, operation_kind, payload_version, payload_json,
            phase, feature_scope, expected_lifecycle_revision, lease_owner, lease_until_ms,
            attempt_count, failure_kind, failure_code, created_at_ms, updated_at_ms,
            durable_at_ms, published_at_ms, terminal_at_ms
            """;
    private static final String TERMINAL_PHASES = "'PUBLISHED', 'COMPENSATED', 'FAILED'";

    private final Connection connection;

    public SqliteOperationStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Operation store connection is required");
        }
        this.connection = connection;
    }

    @Override
    public Optional<OperationEnvelope> find(OperationId operationId) {
        require(operationId, "Operation ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM operation_envelope WHERE operation_id = ?")) {
            statement.setString(1, operationId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readEnvelope(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("operation_find", failure);
        }
    }

    @Override
    public Optional<OperationEnvelope> findByIdempotency(
            OperationKind kind,
            IdempotencyKey idempotencyKey
    ) {
        require(kind, "Operation kind");
        require(idempotencyKey, "Idempotency key");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + """
                         FROM operation_envelope
                         WHERE operation_kind = ? AND idempotency_key = ?
                        """)) {
            statement.setString(1, kind.toString());
            statement.setString(2, idempotencyKey.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readEnvelope(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("operation_find_idempotency", failure);
        }
    }

    @Override
    public PersistenceMutationResult<OperationEnvelope> prepare(PreparedOperation operation) {
        require(operation, "Prepared operation");
        Optional<OperationEnvelope> idempotent =
                findByIdempotency(operation.kind(), operation.idempotencyKey());
        if (idempotent.isPresent()) {
            return matchesPreparation(idempotent.get(), operation)
                    ? PersistenceMutationResult.applied(idempotent.get())
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        Optional<OperationEnvelope> reusedId = find(operation.operationId());
        if (reusedId.isPresent()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try {
            insertEnvelope(operation);
            insertParticipants(operation);
            return PersistenceMutationResult.applied(find(operation.operationId()).orElseThrow());
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("operation_prepare", failure);
        }
    }

    @Override
    public PersistenceMutationResult<OperationEnvelope> transition(
            OperationTransition transition
    ) {
        require(transition, "Operation transition");
        OperationEnvelope current = find(transition.operationId()).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (current.phase() == transition.nextPhase()) {
            return matchesCompletedTransition(current, transition)
                    ? PersistenceMutationResult.applied(current)
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        if (current.phase() != transition.expectedPhase()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.PHASE_MISMATCH);
        }
        if (!java.util.Objects.equals(current.leaseOwner(), transition.expectedLeaseOwner())) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        OperationEnvelope next = transitioned(current, transition);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE operation_envelope
                SET phase = ?, lease_owner = ?, lease_until_ms = ?, failure_kind = ?,
                    failure_code = ?, updated_at_ms = ?, durable_at_ms = ?,
                    published_at_ms = ?, terminal_at_ms = ?
                WHERE operation_id = ? AND phase = ?
                  AND ((lease_owner = ?) OR (lease_owner IS NULL AND ? IS NULL))
                """)) {
            bindTransition(statement, next, transition);
            if (statement.executeUpdate() != 1) {
                return classifyFailedTransition(transition);
            }
            return PersistenceMutationResult.applied(next);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("operation_transition", failure);
        }
    }

    @Override
    public PersistenceMutationResult<OperationEnvelope> acquireLease(
            OperationLeaseRequest request
    ) {
        require(request, "Operation lease request");
        OperationEnvelope current = find(request.operationId()).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (current.phase().isTerminal()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.PHASE_MISMATCH);
        }
        if (current.leasedAt(request.nowMs())
                && !request.leaseOwner().equals(current.leaseOwner())) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
        }
        boolean renewal = request.leaseOwner().equals(current.leaseOwner())
                && current.leasedAt(request.nowMs());
        if (!renewal && current.attemptCount() == Integer.MAX_VALUE) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        int attempts = renewal ? current.attemptCount() : current.attemptCount() + 1;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE operation_envelope
                SET lease_owner = ?, lease_until_ms = ?, attempt_count = ?, updated_at_ms = ?
                WHERE operation_id = ?
                  AND phase NOT IN ('PUBLISHED', 'COMPENSATED', 'FAILED')
                  AND (lease_owner IS NULL OR lease_until_ms <= ? OR lease_owner = ?)
                """)) {
            statement.setString(1, request.leaseOwner());
            statement.setLong(2, request.leaseUntilMs());
            statement.setInt(3, attempts);
            statement.setLong(4, request.nowMs());
            statement.setString(5, request.operationId().toString());
            statement.setLong(6, request.nowMs());
            statement.setString(7, request.leaseOwner());
            if (statement.executeUpdate() != 1) {
                return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
            }
            return PersistenceMutationResult.applied(withLease(current, request, attempts));
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("operation_acquire_lease", failure);
        }
    }

    @Override
    public List<OperationEnvelope> findRecoverable(long nowMs, int limit) {
        if (limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException("Recovery operation limit must be between 1 and 10000");
        }
        String sql = "SELECT " + SELECT_COLUMNS + """
                 FROM operation_envelope
                 WHERE phase NOT IN (%s)
                   AND (lease_owner IS NULL OR lease_until_ms <= ?)
                 ORDER BY created_at_ms, operation_id
                 LIMIT ?
                """.formatted(TERMINAL_PHASES);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nowMs);
            statement.setInt(2, limit);
            ArrayList<OperationEnvelope> operations = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    operations.add(readEnvelope(row));
                }
            }
            return List.copyOf(operations);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("operation_find_recoverable", failure);
        }
    }

    private void insertEnvelope(PreparedOperation operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operation_envelope(
                    operation_id, idempotency_key, operation_kind, payload_version,
                    payload_json, phase, feature_scope, expected_lifecycle_revision,
                    lease_owner, lease_until_ms, attempt_count, failure_kind, failure_code,
                    created_at_ms, updated_at_ms, durable_at_ms, published_at_ms, terminal_at_ms
                ) VALUES (?, ?, ?, ?, ?, 'PREPARED', ?, ?, NULL, 0, 0,
                          NULL, NULL, ?, ?, NULL, NULL, NULL)
                """)) {
            statement.setString(1, operation.operationId().toString());
            statement.setString(2, operation.idempotencyKey().toString());
            statement.setString(3, operation.kind().toString());
            statement.setInt(4, operation.payloadVersion());
            statement.setString(5, operation.payloadJson());
            statement.setString(6, operation.featureScope());
            setNullableLong(statement, 7, operation.expectedLifecycleRevision());
            statement.setLong(8, operation.createdAtMs());
            statement.setLong(9, operation.createdAtMs());
            statement.executeUpdate();
        }
    }

    private void insertParticipants(PreparedOperation operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operation_participant(operation_id, scope_type, scope_key)
                VALUES (?, ?, ?)
                """)) {
            for (OperationScope scope : operation.participants()) {
                statement.setString(1, operation.operationId().toString());
                statement.setString(2, scope.type().name());
                statement.setString(3, scope.key());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private boolean matchesPreparation(OperationEnvelope existing, PreparedOperation requested) {
        return existing.kind().equals(requested.kind())
                && existing.idempotencyKey().equals(requested.idempotencyKey())
                && existing.payloadVersion() == requested.payloadVersion()
                && existing.payloadJson().equals(requested.payloadJson())
                && existing.featureScope().equals(requested.featureScope())
                && java.util.Objects.equals(
                        existing.expectedLifecycleRevision(),
                        requested.expectedLifecycleRevision()
                )
                && semanticParticipants(existing.participants())
                .equals(semanticParticipants(requested.participants()));
    }

    private List<OperationScope> semanticParticipants(List<OperationScope> participants) {
        return participants.stream()
                .filter(scope -> scope.type() != OperationScopeType.OPERATION)
                .sorted()
                .toList();
    }

    private boolean matchesCompletedTransition(OperationEnvelope current,
                                               OperationTransition transition) {
        return java.util.Objects.equals(current.failureKind(), transition.failureKind())
                && java.util.Objects.equals(current.failureCode(), transition.failureCode());
    }

    private OperationEnvelope transitioned(OperationEnvelope current,
                                           OperationTransition transition) {
        boolean clearLease = transition.nextPhase().isTerminal()
                || transition.nextPhase() == OperationPhase.RETRYABLE
                || transition.nextPhase() == OperationPhase.UNKNOWN;
        Long durableAt = current.durableAtMs();
        Long publishedAt = current.publishedAtMs();
        Long terminalAt = current.terminalAtMs();
        if (transition.nextPhase() == OperationPhase.DURABLE && durableAt == null) {
            durableAt = transition.transitionedAtMs();
        }
        if (transition.nextPhase() == OperationPhase.PUBLISHED && publishedAt == null) {
            publishedAt = transition.transitionedAtMs();
        }
        if (transition.nextPhase().isTerminal() && terminalAt == null) {
            terminalAt = transition.transitionedAtMs();
        }
        return new OperationEnvelope(
                current.operationId(), current.idempotencyKey(), current.kind(),
                current.payloadVersion(), current.payloadJson(), transition.nextPhase(),
                current.featureScope(), current.expectedLifecycleRevision(),
                clearLease ? null : current.leaseOwner(),
                clearLease ? 0 : current.leaseUntilMs(),
                current.attemptCount(), transition.failureKind(), transition.failureCode(),
                current.createdAtMs(), transition.transitionedAtMs(),
                durableAt, publishedAt, terminalAt, current.participants()
        );
    }

    private OperationEnvelope withLease(OperationEnvelope current,
                                        OperationLeaseRequest request,
                                        int attempts) {
        return new OperationEnvelope(
                current.operationId(), current.idempotencyKey(), current.kind(),
                current.payloadVersion(), current.payloadJson(), current.phase(),
                current.featureScope(), current.expectedLifecycleRevision(),
                request.leaseOwner(), request.leaseUntilMs(), attempts,
                current.failureKind(), current.failureCode(), current.createdAtMs(),
                request.nowMs(), current.durableAtMs(), current.publishedAtMs(),
                current.terminalAtMs(), current.participants()
        );
    }

    private void bindTransition(PreparedStatement statement,
                                OperationEnvelope next,
                                OperationTransition transition) throws SQLException {
        statement.setString(1, next.phase().name());
        setNullableText(statement, 2, next.leaseOwner());
        statement.setLong(3, next.leaseUntilMs());
        setNullableText(statement, 4, next.failureKind());
        setNullableText(statement, 5, next.failureCode());
        statement.setLong(6, next.updatedAtMs());
        setNullableLong(statement, 7, next.durableAtMs());
        setNullableLong(statement, 8, next.publishedAtMs());
        setNullableLong(statement, 9, next.terminalAtMs());
        statement.setString(10, next.operationId().toString());
        statement.setString(11, transition.expectedPhase().name());
        setNullableText(statement, 12, transition.expectedLeaseOwner());
        setNullableText(statement, 13, transition.expectedLeaseOwner());
    }

    private PersistenceMutationResult<OperationEnvelope> classifyFailedTransition(
            OperationTransition transition
    ) {
        OperationEnvelope current = find(transition.operationId()).orElse(null);
        if (current == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (current.phase() != transition.expectedPhase()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.PHASE_MISMATCH);
        }
        return PersistenceMutationResult.rejected(PersistenceMutationStatus.FENCE_MISMATCH);
    }

    private OperationEnvelope readEnvelope(ResultSet row) throws SQLException {
        String expectedRevision = row.getString("expected_lifecycle_revision");
        String leaseOwner = row.getString("lease_owner");
        return new OperationEnvelope(
                OperationId.parse(row.getString("operation_id")),
                new IdempotencyKey(row.getString("idempotency_key")),
                new OperationKind(row.getString("operation_kind")),
                row.getInt("payload_version"),
                row.getString("payload_json"),
                OperationPhase.valueOf(row.getString("phase")),
                row.getString("feature_scope"),
                expectedRevision == null
                        ? null
                        : new LifecycleRevision(Long.parseLong(expectedRevision)),
                leaseOwner,
                row.getLong("lease_until_ms"),
                row.getInt("attempt_count"),
                row.getString("failure_kind"),
                row.getString("failure_code"),
                row.getLong("created_at_ms"),
                row.getLong("updated_at_ms"),
                nullableLong(row, "durable_at_ms"),
                nullableLong(row, "published_at_ms"),
                nullableLong(row, "terminal_at_ms"),
                readParticipants(OperationId.parse(row.getString("operation_id")))
        );
    }

    private List<OperationScope> readParticipants(OperationId operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT scope_type, scope_key
                FROM operation_participant
                WHERE operation_id = ?
                ORDER BY scope_type, scope_key
                """)) {
            statement.setString(1, operationId.toString());
            ArrayList<OperationScope> participants = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    participants.add(new OperationScope(
                            OperationScopeType.valueOf(row.getString("scope_type")),
                            row.getString("scope_key")
                    ));
                }
            }
            return List.copyOf(participants);
        }
    }

    private Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private void setNullableLong(PreparedStatement statement,
                                 int index,
                                 LifecycleRevision value) throws SQLException {
        setNullableLong(statement, index, value == null ? null : value.value());
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private void setNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
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

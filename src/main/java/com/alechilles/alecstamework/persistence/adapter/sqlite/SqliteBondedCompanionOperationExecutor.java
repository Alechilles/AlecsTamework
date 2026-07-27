package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionOperationClaims.Claim;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEvidence;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionOperationProbe;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStoreResult;
import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/** Owns idempotency claims, domain translation, and terminal operation proof. */
final class SqliteBondedCompanionOperationExecutor {
    private static final Gson GSON = new Gson();
    private final SqliteConnectionFactory connections;
    private final SqliteBondedCompanionOperationClaims claims =
            new SqliteBondedCompanionOperationClaims();

    SqliteBondedCompanionOperationExecutor(
            SqliteConnectionFactory connections
    ) {
        this.connections = connections;
    }

    <R, D> Optional<BondedCompanionStoreResult<D>> find(
            BondedCompanionOperationProbe operation,
            Class<R> storedType,
            Translation<R, D> translation
    ) {
        try (Connection connection = connections.openReadConnection()) {
            Optional<Claim> existing = claims.existing(connection, operation);
            return existing.map(claim -> replay(
                    claim, storedType, translation));
        } catch (SQLException failure) {
            throw new IllegalStateException("bonded-operation-read-failed", failure);
        }
    }

    <R, D> Optional<BondedCompanionStoreResult<D>> find(
            BondedCompanionOperation operation,
            Class<R> storedType,
            Translation<R, D> translation
    ) {
        try (Connection connection = connections.openReadConnection()) {
            Optional<Claim> existing = claims.existing(connection, operation);
            return existing.map(claim -> replay(
                    claim, storedType, translation));
        } catch (SQLException failure) {
            throw new IllegalStateException("bonded-operation-read-failed", failure);
        }
    }

    <R, D> BondedCompanionStoreResult<D> mutate(
            BondedCompanionOperation operation,
            Class<R> storedType,
            Mutation<R> mutation,
            Translation<R, D> translation
    ) {
        return execute(operation, null, storedType,
                connection -> mutation.apply(
                        new SqliteBondedCompanionStore(connection)),
                translation, null);
    }

    <R, D> BondedCompanionStoreResult<D> mutate(
            BondedCompanionOperation operation,
            Long expectedRevision,
            Class<R> storedType,
            Mutation<R> mutation,
            Translation<R, D> translation
    ) {
        return execute(operation, expectedRevision, storedType,
                connection -> mutation.apply(
                        new SqliteBondedCompanionStore(connection)),
                translation, null);
    }

    <R, D> BondedCompanionStoreResult<D> mutate(
            BondedCompanionOperation operation,
            Long expectedRevision,
            Class<R> storedType,
            Mutation<R> mutation,
            Translation<R, D> translation,
            BondedCompanionCaptureEvidence captureEvidence
    ) {
        return execute(operation, expectedRevision, storedType,
                connection -> mutation.apply(
                        new SqliteBondedCompanionStore(connection)),
                translation, captureEvidence);
    }

    <R, D> BondedCompanionStoreResult<D> mutateConnection(
            BondedCompanionOperation operation,
            Long expectedRevision,
            Class<R> storedType,
            ConnectionMutation<R> mutation,
            Translation<R, D> translation,
            BondedCompanionCaptureEvidence captureEvidence
    ) {
        return execute(operation, expectedRevision, storedType, mutation,
                translation, captureEvidence);
    }

    private <R, D> BondedCompanionStoreResult<D> execute(
            BondedCompanionOperation operation,
            Long expectedRevision,
            Class<R> storedType,
            ConnectionMutation<R> mutation,
            Translation<R, D> translation,
            BondedCompanionCaptureEvidence captureEvidence
    ) {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            Claim claim = claims.claim(connection, operation, expectedRevision,
                    placeholder(storedType, operation.storeLeaseIdentity()));
            if (!claim.created()) {
                BondedCompanionStoreResult<D> replay = replay(
                        claim, storedType, translation);
                connection.commit();
                return replay;
            }
            var lowLevel = mutation.apply(connection);
            BondedCompanionStoreResult<D> result = domainResult(
                    lowLevel, translation);
            if (result.code()
                    == BondedCompanionStoreResult.Code.STORAGE_FAILURE) {
                connection.rollback();
                return result;
            }
            terminalize(connection, operation, lowLevel, storedType,
                    captureEvidence);
            connection.commit();
            return result;
        } catch (Exception failure) {
            rollback(connection, failure);
            return new BondedCompanionStoreResult<>(
                    BondedCompanionStoreResult.Code.STORAGE_FAILURE, null,
                    "bonded-transaction-failed", false);
        } finally {
            close(connection);
        }
    }

    BondedCompanionCaptureEvidence captureEvidence(String resultJson) {
        StoredResult stored = GSON.fromJson(resultJson, StoredResult.class);
        if (stored == null || stored.captureEvidence == null) {
            throw new IllegalStateException("bonded-capture-evidence-missing");
        }
        return stored.captureEvidence;
    }

    private <R, D> BondedCompanionStoreResult<D> replay(
            Claim claim,
            Class<R> storedType,
            Translation<R, D> translation
    ) {
        if (!claim.matches()) return new BondedCompanionStoreResult<>(
                BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT, null,
                "idempotency-key-request-mismatch", false);
        if (claim.resultJson() == null) {
            return new BondedCompanionStoreResult<>(
                    BondedCompanionStoreResult.Code.STORAGE_FAILURE, null,
                    "terminal-operation-result-missing", false);
        }
        StoredResult envelope = GSON.fromJson(
                claim.resultJson(), StoredResult.class);
        String expectedType = storedTypeName(storedType);
        if (!expectedType.equals(envelope.valueType)) {
            return new BondedCompanionStoreResult<>(
                    BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT, null,
                    "idempotency-key-result-type-mismatch", false);
        }
        R row = envelope.value == null ? null
                : GSON.fromJson(envelope.value, storedType);
        D value = row == null ? null : translation.apply(row);
        return new BondedCompanionStoreResult<>(
                BondedCompanionStoreResult.Code.valueOf(envelope.code), value,
                envelope.reason, true);
    }

    private void terminalize(
            Connection connection,
            BondedCompanionOperation operation,
            SqliteBondedCompanionStore.MutationResult<?> result,
            Class<?> storedType,
            BondedCompanionCaptureEvidence captureEvidence
    ) throws SQLException {
        String state = result.code()
                == SqliteBondedCompanionStore.MutationCode.APPLIED
                ? "SUCCEEDED" : "REJECTED";
        StoredResult stored = new StoredResult(mapCode(result.code()).name(),
                result.reason(), storedTypeName(storedType),
                result.value() == null ? null : GSON.toJson(result.value()),
                result.code() == SqliteBondedCompanionStore.MutationCode.APPLIED
                        ? captureEvidence : null,
                null, operation.storeLeaseIdentity());
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE bonded_companion_operation
                SET operation_state = ?, result_json = ?, updated_at_ms = ?,
                    expires_at_ms = ?
                WHERE caller_namespace = ? AND idempotency_key = ?
                  AND operation_type = ? AND request_hash = ?
                """)) {
            update.setString(1, state);
            update.setString(2, GSON.toJson(stored));
            update.setLong(3, operation.attemptedAtMs());
            update.setLong(4, operation.retainedUntilMs());
            update.setString(5, operation.callerNamespace());
            update.setString(6, operation.idempotencyKey());
            update.setString(7, operation.type().name());
            update.setString(8, operation.requestHash());
            if (update.executeUpdate() != 1) {
                throw new SQLException("bonded_operation_terminalize_race");
            }
        }
    }

    private <R, D> BondedCompanionStoreResult<D> domainResult(
            SqliteBondedCompanionStore.MutationResult<R> result,
            Translation<R, D> translation
    ) {
        D value = result.value() == null ? null
                : translation.apply(result.value());
        return new BondedCompanionStoreResult<>(mapCode(result.code()), value,
                result.reason(), result.code()
                == SqliteBondedCompanionStore.MutationCode.IDEMPOTENT_REPLAY);
    }

    private BondedCompanionStoreResult.Code mapCode(
            SqliteBondedCompanionStore.MutationCode code
    ) {
        return switch (code) {
            case APPLIED, IDEMPOTENT_REPLAY ->
                    BondedCompanionStoreResult.Code.APPLIED;
            case NOT_FOUND -> BondedCompanionStoreResult.Code.NOT_FOUND;
            case NOT_OWNER -> BondedCompanionStoreResult.Code.NOT_OWNER;
            case REVISION_CONFLICT ->
                    BondedCompanionStoreResult.Code.REVISION_CONFLICT;
            case INVALID_STATE -> BondedCompanionStoreResult.Code.INVALID_STATE;
            case CONFLICT -> BondedCompanionStoreResult.Code.CONFLICT;
            case VALIDATION_FAILED ->
                    BondedCompanionStoreResult.Code.VALIDATION_FAILED;
            case STORAGE_FAILURE ->
                    BondedCompanionStoreResult.Code.STORAGE_FAILURE;
        };
    }

    private String storedTypeName(Class<?> storedType) {
        if (storedType == SqliteBondedCompanionProfileRow.class) return "PROFILE";
        if (storedType == SqliteBondedCompanionLeaseRow.class) return "LEASE";
        if (storedType == SqliteBondedCompanionExtensionDataRow.class) {
            return "EXTENSION";
        }
        if (storedType == SqliteBondedCompanionCleanupRow.class) return "CLEANUP";
        throw new IllegalArgumentException("unsupported bonded result type");
    }

    private String placeholder(
            Class<?> storedType,
            BondedCompanionOperation.StoreLeaseIdentity storeLeaseIdentity
    ) {
        return GSON.toJson(new StoredResult(
                BondedCompanionStoreResult.Code.CONFLICT.name(),
                "transaction-not-terminal", storedTypeName(storedType), null,
                null, null, storeLeaseIdentity));
    }

    private void rollback(Connection connection, Exception original) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }

    private void close(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private record StoredResult(
            String code,
            String reason,
            String valueType,
            String value,
            BondedCompanionCaptureEvidence captureEvidence,
            Long captureEventPublishedAtMs,
            BondedCompanionOperation.StoreLeaseIdentity storeLeaseIdentity
    ) {
    }

    @FunctionalInterface
    interface Mutation<T> {
        SqliteBondedCompanionStore.MutationResult<T> apply(
                SqliteBondedCompanionStore store);
    }

    @FunctionalInterface
    interface ConnectionMutation<T> {
        SqliteBondedCompanionStore.MutationResult<T> apply(
                Connection connection);
    }

    @FunctionalInterface
    interface Translation<S, T> {
        T apply(S source);
    }
}

package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStore;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreResult;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Public connection-owning SQLite implementation of the bonded domain store. */
public final class SqliteBondedCompanionDatabase implements BondedCompanionStore {
    private static final Gson GSON = new Gson();
    private final SqliteConnectionFactory connections;
    private final SqliteBondedCompanionMapper mapper = new SqliteBondedCompanionMapper();

    /** Creates a safe store that owns every connection and transaction. */
    public SqliteBondedCompanionDatabase(@Nonnull Path databasePath) {
        connections = new SqliteConnectionFactory(
                Objects.requireNonNull(databasePath, "databasePath"));
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            createProfile(BondedCompanionOperation operation,
                          BondedCompanionRecord.Profile profile) {
        requireScope(operation, profile.ownerUuid(), profile.rosterId(), profile.profileId());
        return mutate(operation, SqliteBondedCompanionProfileRow.class,
                store -> store.createProfile(mapper.toRow(profile)), mapper::toDomain);
    }

    @Override public List<BondedCompanionRecord.Profile> listProfiles(
            UUID ownerUuid, String rosterId) {
        return read(store -> store.listProfiles(ownerUuid, rosterId).stream()
                .map(mapper::toDomain).toList());
    }

    @Override public Optional<BondedCompanionRecord.Profile> findProfile(
            UUID ownerUuid, String rosterId, String profileId) {
        return read(store -> store.findProfile(ownerUuid, rosterId, profileId)
                .map(mapper::toDomain));
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.Lease>
            acquireLease(BondedCompanionOperation operation, long expectedRevision,
                         BondedCompanionRecord.Lease lease) {
        requireScope(operation, operation.ownerUuid(), operation.rosterId(), lease.profileId());
        return mutate(operation, SqliteBondedCompanionLeaseRow.class,
                store -> store.acquireLease(operation.ownerUuid(), operation.rosterId(),
                        expectedRevision, mapper.toRow(lease)), mapper::toDomain);
    }

    @Override public List<BondedCompanionRecord.Lease> findActiveLeases(
            UUID ownerUuid, String rosterId) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return queryActiveLeases(ownerUuid, requireText(rosterId, "rosterId"));
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            reviveProfile(BondedCompanionOperation operation,
                          long expectedRevision, long updatedAtMs) {
        String profileId = Objects.requireNonNull(operation.profileId(), "operation.profileId");
        return mutate(operation, SqliteBondedCompanionProfileRow.class,
                store -> store.reviveProfile(operation.ownerUuid(), operation.rosterId(),
                        profileId, expectedRevision, updatedAtMs), mapper::toDomain);
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            updateSnapshot(BondedCompanionOperation operation,
                           long expectedRevision,
                           BondedCompanionPayload snapshot,
                           long updatedAtMs) {
        String profileId = Objects.requireNonNull(
                operation.profileId(), "operation.profileId");
        return mutate(operation, SqliteBondedCompanionProfileRow.class,
                store -> store.updateSnapshot(
                        operation.ownerUuid(), operation.rosterId(), profileId,
                        expectedRevision, mapper.payloadJson(snapshot), updatedAtMs),
                mapper::toDomain);
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            releaseLease(BondedCompanionOperation operation,
                         long expectedRevision, String leaseToken,
                         long updatedAtMs) {
        String profileId = Objects.requireNonNull(
                operation.profileId(), "operation.profileId");
        return mutate(operation, SqliteBondedCompanionProfileRow.class,
                store -> store.releaseLease(
                        operation.ownerUuid(), operation.rosterId(), profileId,
                        leaseToken, expectedRevision, updatedAtMs), mapper::toDomain);
    }

    @Override public Optional<BondedCompanionRecord.ExtensionData>
            findExtensionData(UUID ownerUuid, String rosterId,
                              String profileId, String namespace) {
        return read(store -> store.findExtensionData(
                ownerUuid, rosterId, profileId, namespace).map(mapper::toDomain));
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.ExtensionData>
            compareAndSetExtensionData(
                    BondedCompanionOperation operation,
                    BondedCompanionRecord.ExtensionData extension,
                    long expectedRevision) {
        requireScope(operation, operation.ownerUuid(), operation.rosterId(),
                extension.profileId());
        return mutate(operation, SqliteBondedCompanionExtensionDataRow.class,
                store -> store.compareAndSetExtensionData(
                        operation.ownerUuid(), operation.rosterId(),
                        mapper.toRow(extension), expectedRevision), mapper::toDomain);
    }

    @Override public List<BondedCompanionRecord.Lease> findExpiredLeases(
            long nowMs, int limit) {
        return read(store -> store.findExpiredLeases(nowMs, limit).stream()
                .map(mapper::toDomain).toList());
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.Cleanup>
            enqueueCleanup(BondedCompanionOperation operation,
                           BondedCompanionRecord.Cleanup cleanup) {
        requireScope(operation, cleanup.ownerUuid(), cleanup.rosterId(),
                cleanup.profileId());
        return mutate(operation, SqliteBondedCompanionCleanupRow.class,
                store -> store.enqueueCleanup(
                        operation.ownerUuid(), operation.rosterId(),
                        mapper.toRow(cleanup)), mapper::toDomain);
    }

    @Override public List<BondedCompanionRecord.Cleanup> listCleanup(
            UUID ownerUuid, String rosterId, int limit) {
        return read(store -> store.listCleanup(ownerUuid, rosterId, limit).stream()
                .map(mapper::toDomain).toList());
    }

    @Override public int pruneCleanup(long nowMs, int limit) {
        return integerWrite(store -> store.pruneCleanup(nowMs, limit));
    }

    @Override public int pruneOperations(long nowMs, int limit) {
        return integerWrite(store -> store.pruneOperations(nowMs, limit));
    }

    private <R, D> BondedCompanionStoreResult<D> mutate(
            BondedCompanionOperation operation, Class<R> storedType,
            Mutation<R> mutation, Translation<R, D> translation) {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            Claim claim = claim(connection, operation);
            if (!claim.created()) {
                BondedCompanionStoreResult<D> replay = replay(claim, storedType, translation);
                connection.commit();
                return replay;
            }
            var lowLevel = mutation.apply(new SqliteBondedCompanionStore(connection));
            BondedCompanionStoreResult<D> result = domainResult(lowLevel, translation);
            if (result.code() == BondedCompanionStoreResult.Code.STORAGE_FAILURE) {
                connection.rollback();
                return result;
            }
            terminalize(connection, operation, lowLevel, storedType);
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

    private Claim claim(Connection connection, BondedCompanionOperation operation)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO bonded_companion_operation(
                    caller_namespace, idempotency_key, owner_uuid, roster_id,
                    profile_id, operation_type, request_hash, operation_state,
                    result_json, created_at_ms, updated_at_ms, expires_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', NULL, ?, ?, ?)
                """)) {
            bindOperation(insert, operation);
            if (insert.executeUpdate() == 1) return new Claim(true, null);
        }
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT owner_uuid, roster_id, profile_id, operation_type,
                       request_hash, operation_state, result_json
                FROM bonded_companion_operation
                WHERE caller_namespace = ? AND idempotency_key = ?
                """)) {
            select.setString(1, operation.callerNamespace());
            select.setString(2, operation.idempotencyKey());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) throw new SQLException("bonded_operation_claim_disappeared");
                boolean matches = operation.ownerUuid().toString().equals(row.getString(1))
                        && operation.rosterId().equals(row.getString(2))
                        && Objects.equals(operation.profileId(), row.getString(3))
                        && operation.type().name().equals(row.getString(4))
                        && operation.requestHash().equals(row.getString(5));
                return new Claim(false, matches ? row.getString(7) : null,
                        matches, row.getString(6));
            }
        }
    }

    private <R, D> BondedCompanionStoreResult<D> replay(
            Claim claim, Class<R> storedType, Translation<R, D> translation) {
        if (!claim.matches()) return new BondedCompanionStoreResult<>(
                BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT, null,
                "idempotency-key-request-mismatch", false);
        if (claim.resultJson() == null || "PENDING".equals(claim.state()))
            return new BondedCompanionStoreResult<>(BondedCompanionStoreResult.Code.CONFLICT,
                    null, "operation-still-pending", false);
        StoredResult envelope = GSON.fromJson(claim.resultJson(), StoredResult.class);
        String expectedType = storedTypeName(storedType);
        if (!"NONE".equals(envelope.valueType)
                && !expectedType.equals(envelope.valueType)) {
            return new BondedCompanionStoreResult<>(
                    BondedCompanionStoreResult.Code.IDEMPOTENCY_CONFLICT, null,
                    "idempotency-key-result-type-mismatch", false);
        }
        R row = envelope.value == null ? null : GSON.fromJson(envelope.value, storedType);
        D value = row == null ? null : translation.apply(row);
        return new BondedCompanionStoreResult<>(
                BondedCompanionStoreResult.Code.valueOf(envelope.code), value,
                envelope.reason, true);
    }

    private void terminalize(Connection connection, BondedCompanionOperation operation,
                             SqliteBondedCompanionStore.MutationResult<?> result,
                             Class<?> storedType)
            throws SQLException {
        String state = result.code() == SqliteBondedCompanionStore.MutationCode.APPLIED
                ? "SUCCEEDED" : "REJECTED";
        StoredResult stored = new StoredResult(mapCode(result.code()).name(),
                result.reason(), storedTypeName(storedType),
                result.value() == null ? null : GSON.toJson(result.value()));
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE bonded_companion_operation
                SET operation_state = ?, result_json = ?, updated_at_ms = ?
                WHERE caller_namespace = ? AND idempotency_key = ?
                  AND operation_state = 'PENDING'
                """)) {
            update.setString(1, state); update.setString(2, GSON.toJson(stored));
            update.setLong(3, operation.attemptedAtMs());
            update.setString(4, operation.callerNamespace());
            update.setString(5, operation.idempotencyKey());
            if (update.executeUpdate() != 1) throw new SQLException("bonded_operation_terminalize_race");
        }
    }

    private String storedTypeName(Class<?> storedType) {
        if (storedType == SqliteBondedCompanionProfileRow.class) return "PROFILE";
        if (storedType == SqliteBondedCompanionLeaseRow.class) return "LEASE";
        if (storedType == SqliteBondedCompanionExtensionDataRow.class) return "EXTENSION";
        if (storedType == SqliteBondedCompanionCleanupRow.class) return "CLEANUP";
        throw new IllegalArgumentException("unsupported bonded result type");
    }

    private List<BondedCompanionRecord.Lease> queryActiveLeases(UUID owner, String roster) {
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT l.profile_id, l.lease_token, l.live_npc_uuid,
                            l.world_key, l.started_at_ms, l.expires_at_ms,
                            l.projection_state
                     FROM bonded_companion_lease l
                     JOIN bonded_companion_profile p ON p.profile_id = l.profile_id
                     WHERE p.owner_uuid = ? AND p.roster_id = ?
                     ORDER BY l.profile_id
                     """)) {
            statement.setString(1, owner.toString()); statement.setString(2, roster);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<BondedCompanionRecord.Lease> result = new ArrayList<>();
                while (rows.next()) result.add(mapper.toDomain(
                        SqliteBondedCompanionRows.readLease(rows)));
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("bonded-read-failed", failure);
        }
    }

    private <R, D> BondedCompanionStoreResult<D> domainResult(
            SqliteBondedCompanionStore.MutationResult<R> result,
            Translation<R, D> translation) {
        D value = result.value() == null ? null : translation.apply(result.value());
        return new BondedCompanionStoreResult<>(mapCode(result.code()), value,
                result.reason(), result.code() == SqliteBondedCompanionStore.MutationCode.IDEMPOTENT_REPLAY);
    }

    private BondedCompanionStoreResult.Code mapCode(SqliteBondedCompanionStore.MutationCode code) {
        return switch (code) {
            case APPLIED, IDEMPOTENT_REPLAY -> BondedCompanionStoreResult.Code.APPLIED;
            case NOT_FOUND -> BondedCompanionStoreResult.Code.NOT_FOUND;
            case NOT_OWNER -> BondedCompanionStoreResult.Code.NOT_OWNER;
            case REVISION_CONFLICT -> BondedCompanionStoreResult.Code.REVISION_CONFLICT;
            case INVALID_STATE -> BondedCompanionStoreResult.Code.INVALID_STATE;
            case CONFLICT -> BondedCompanionStoreResult.Code.CONFLICT;
            case VALIDATION_FAILED -> BondedCompanionStoreResult.Code.VALIDATION_FAILED;
            case STORAGE_FAILURE -> BondedCompanionStoreResult.Code.STORAGE_FAILURE;
        };
    }

    private void bindOperation(PreparedStatement statement,
                               BondedCompanionOperation operation) throws SQLException {
        statement.setString(1, operation.callerNamespace()); statement.setString(2, operation.idempotencyKey());
        statement.setString(3, operation.ownerUuid().toString()); statement.setString(4, operation.rosterId());
        statement.setString(5, operation.profileId()); statement.setString(6, operation.type().name());
        statement.setString(7, operation.requestHash()); statement.setLong(8, operation.attemptedAtMs());
        statement.setLong(9, operation.attemptedAtMs()); statement.setLong(10, operation.retainedUntilMs());
    }

    private <T> T read(Read<T> work) {
        try (Connection connection = connections.openReadConnection()) {
            return work.apply(new SqliteBondedCompanionStore(connection));
        } catch (SQLException failure) {
            throw new IllegalStateException("bonded-read-failed", failure);
        }
    }

    private int integerWrite(IntegerMutation mutation) {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            int changed = mutation.apply(new SqliteBondedCompanionStore(connection));
            connection.commit();
            return changed;
        } catch (Exception failure) {
            rollback(connection, failure);
            throw new IllegalStateException("bonded-transaction-failed", failure);
        } finally {
            close(connection);
        }
    }

    private void requireScope(BondedCompanionOperation operation, UUID owner,
                              String roster, String profile) {
        Objects.requireNonNull(operation, "operation");
        if (!operation.ownerUuid().equals(owner) || !operation.rosterId().equals(roster)
                || !Objects.equals(operation.profileId(), profile))
            throw new IllegalArgumentException("operation scope does not match mutation");
    }

    private String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private void rollback(Connection connection, Exception original) {
        if (connection == null) return;
        try { connection.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); }
    }

    private void close(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (SQLException ignored) { }
    }

    private record Claim(boolean created, String resultJson, boolean matches, String state) {
        private Claim(boolean created, String resultJson) { this(created, resultJson, true, "PENDING"); }
    }
    private record StoredResult(
            String code, String reason, String valueType, String value) {}
    @FunctionalInterface private interface Mutation<T> { SqliteBondedCompanionStore.MutationResult<T> apply(SqliteBondedCompanionStore store); }
    @FunctionalInterface private interface Translation<S, T> { T apply(S source); }
    @FunctionalInterface private interface Read<T> { T apply(SqliteBondedCompanionStore store); }
    @FunctionalInterface private interface IntegerMutation { int apply(SqliteBondedCompanionStore store); }
}

package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionCleanupRow;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionExtensionDataRow;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionLeaseRow;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionOperationRow;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionProfileRow;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Narrow bonded persistence boundary that owns connection transactions while
 * keeping every SQLite adapter transaction-local.
 */
public final class BondedCompanionStore {
    private final SqliteConnectionFactory connections;

    public BondedCompanionStore(@Nonnull SqliteConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Nonnull
    public SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionProfileRow> createProfile(
            @Nonnull SqliteBondedCompanionProfileRow row
    ) {
        return write(store -> store.createProfile(row));
    }

    @Nonnull
    public List<SqliteBondedCompanionProfileRow> listProfiles(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId
    ) {
        return read(store -> store.listProfiles(ownerUuid, rosterId));
    }

    @Nonnull
    public Optional<SqliteBondedCompanionProfileRow> findProfile(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId
    ) {
        return read(store -> store.findProfile(ownerUuid, rosterId, profileId));
    }

    @Nonnull
    public SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionProfileRow> updateSnapshot(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision,
            @Nonnull String snapshotJson,
            long updatedAtMs
    ) {
        return write(store -> store.updateSnapshot(
                ownerUuid, rosterId, profileId, expectedRevision,
                snapshotJson, updatedAtMs
        ));
    }

    @Nonnull
    public SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionLeaseRow> acquireLease(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            long expectedRevision,
            @Nonnull SqliteBondedCompanionLeaseRow lease
    ) {
        return write(store -> store.acquireLease(
                ownerUuid, rosterId, expectedRevision, lease
        ));
    }

    @Nonnull
    public SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionProfileRow> releaseLease(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            @Nonnull String leaseToken,
            long expectedRevision,
            long updatedAtMs
    ) {
        return write(store -> store.releaseLease(
                ownerUuid, rosterId, profileId, leaseToken,
                expectedRevision, updatedAtMs
        ));
    }

    @Nonnull
    public SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionProfileRow> reviveProfile(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision,
            long updatedAtMs
    ) {
        return write(store -> store.reviveProfile(
                ownerUuid, rosterId, profileId, expectedRevision, updatedAtMs
        ));
    }

    @Nonnull
    public Optional<SqliteBondedCompanionExtensionDataRow> findExtensionData(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            @Nonnull String namespace
    ) {
        return read(store -> store.findExtensionData(
                ownerUuid, rosterId, profileId, namespace
        ));
    }

    @Nonnull
    public SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionExtensionDataRow> compareAndSetExtensionData(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull SqliteBondedCompanionExtensionDataRow row,
            long expectedRevision
    ) {
        return write(store -> store.compareAndSetExtensionData(
                ownerUuid, rosterId, row, expectedRevision
        ));
    }

    @Nonnull
    public List<SqliteBondedCompanionLeaseRow> findExpiredLeases(
            long nowMs,
            int limit
    ) {
        return read(store -> store.findExpiredLeases(nowMs, limit));
    }

    @Nonnull
    public SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionCleanupRow> enqueueCleanup(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull SqliteBondedCompanionCleanupRow row
    ) {
        return write(store -> store.enqueueCleanup(ownerUuid, rosterId, row));
    }

    @Nonnull
    public List<SqliteBondedCompanionCleanupRow> listCleanup(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            int limit
    ) {
        return read(store -> store.listCleanup(ownerUuid, rosterId, limit));
    }

    public int pruneCleanup(long nowMs, int limit) {
        return integerWrite(store -> store.pruneCleanup(nowMs, limit));
    }

    @Nonnull
    public SqliteBondedCompanionStore.MutationResult<
            SqliteBondedCompanionOperationRow> recordOperation(
            @Nonnull SqliteBondedCompanionOperationRow row
    ) {
        return write(store -> store.recordOperation(row));
    }

    public int pruneOperations(long nowMs, int limit) {
        return integerWrite(store -> store.pruneOperations(nowMs, limit));
    }

    private <T> SqliteBondedCompanionStore.MutationResult<T> write(
            Mutation<T> mutation
    ) {
        Connection connection = null;
        try {
            connection = connections.openWriterConnection();
            connection.setAutoCommit(false);
            var result = mutation.apply(new SqliteBondedCompanionStore(connection));
            if (result.code() == SqliteBondedCompanionStore.MutationCode.APPLIED
                    || result.code() == SqliteBondedCompanionStore.MutationCode
                    .IDEMPOTENT_REPLAY) {
                connection.commit();
            } else {
                connection.rollback();
            }
            return result;
        } catch (Exception failure) {
            rollback(connection, failure);
            return new SqliteBondedCompanionStore.MutationResult<>(
                    SqliteBondedCompanionStore.MutationCode.STORAGE_FAILURE,
                    null,
                    "bonded-transaction-failed"
            );
        } finally {
            close(connection);
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

    private <T> T read(Read<T> read) {
        try (Connection connection = connections.openReadConnection()) {
            return read.apply(new SqliteBondedCompanionStore(connection));
        } catch (SQLException failure) {
            throw new IllegalStateException("bonded-read-failed", failure);
        }
    }

    private void rollback(Connection connection, Exception original) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }

    private void close(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Transaction outcome was already established before close.
        }
    }

    @FunctionalInterface
    private interface Mutation<T> {
        SqliteBondedCompanionStore.MutationResult<T> apply(
                SqliteBondedCompanionStore store
        );
    }

    @FunctionalInterface
    private interface IntegerMutation {
        int apply(SqliteBondedCompanionStore store);
    }

    @FunctionalInterface
    private interface Read<T> {
        T apply(SqliteBondedCompanionStore store);
    }
}

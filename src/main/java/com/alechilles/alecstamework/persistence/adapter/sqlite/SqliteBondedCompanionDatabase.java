package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperation;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionOperationProbe;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEvidence;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEvidenceStore;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStore;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreDiagnostics;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreResult;
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
public final class SqliteBondedCompanionDatabase implements BondedCompanionStore,
        BondedCompanionCaptureEvidenceStore {
    private final SqliteConnectionFactory connections;
    private final SqliteBondedCompanionMapper mapper = new SqliteBondedCompanionMapper();
    private final SqliteBondedCompanionOperationExecutor operations;
    private final SqliteBondedCompanionCaptureEvidenceAccess captureEvents;
    private final SqliteBondedCompanionProfileDeletion deletions;

    /** Creates a safe store that owns every connection and transaction. */
    public SqliteBondedCompanionDatabase(@Nonnull Path databasePath) {
        connections = new SqliteConnectionFactory(
                Objects.requireNonNull(databasePath, "databasePath"));
        operations = new SqliteBondedCompanionOperationExecutor(connections);
        captureEvents = new SqliteBondedCompanionCaptureEvidenceAccess(
                connections);
        deletions = new SqliteBondedCompanionProfileDeletion(connections, mapper);
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            createProfile(BondedCompanionOperation operation,
                          BondedCompanionRecord.Profile profile) {
        requireScope(operation, profile.ownerUuid(), profile.rosterId(), profile.profileId());
        return mutate(operation, SqliteBondedCompanionProfileRow.class,
                store -> store.createProfile(mapper.toRow(profile)), mapper::toDomain);
    }

    @Override
    public BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            createProfile(
                    BondedCompanionOperation operation,
                    BondedCompanionRecord.Profile profile,
                    int maximumOwned
            ) {
        requireScope(operation, profile.ownerUuid(), profile.rosterId(),
                profile.profileId());
        return mutate(operation, SqliteBondedCompanionProfileRow.class,
                store -> store.createProfile(
                        mapper.toRow(profile), maximumOwned),
                mapper::toDomain);
    }

    /** Atomically creates a captured profile, source authority, and cleanup. */
    public BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            createCapturedProfile(
            BondedCompanionOperation operation,
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Cleanup cleanup,
            int maximumOwned,
            BondedCompanionCaptureEvidence captureEvidence
    ) {
        requireScope(operation, profile.ownerUuid(), profile.rosterId(),
                profile.profileId());
        captureEvents.requireMatches(
                operation, profile, cleanup,
                Objects.requireNonNull(captureEvidence, "captureEvidence"));
        SqliteBondedCompanionProfileRow profileRow = mapper.toRow(profile);
        SqliteBondedCompanionCaptureSourceRow sourceRow =
                new SqliteBondedCompanionCaptureSourceRow(
                        captureEvidence, profileRow, operation.requestHash(), null);
        return mutate(operation, null,
                SqliteBondedCompanionProfileRow.class,
                store -> store.createCapturedProfile(
                        profileRow, mapper.toRow(cleanup), sourceRow,
                        maximumOwned),
                mapper::toDomain, captureEvidence);
    }

    @Override
    public Optional<BondedCompanionCaptureEvidence> findCaptureEvidence(
            UUID ownerUuid,
            String rosterId,
            UUID sourceNpcUuid
    ) {
        return captureEvents.find(ownerUuid, rosterId, sourceNpcUuid);
    }

    /** Internal global-source fence; the public evidence API stays owner scoped. */
    List<BondedCompanionCaptureEvidence> findCaptureEvidenceBySource(
            UUID sourceNpcUuid
    ) {
        return captureEvents.findBySource(sourceNpcUuid, 2);
    }

    /** Internal immutable source authority, including the capture-time profile. */
    List<SqliteBondedCompanionCaptureSourceRow> findCaptureSourcesBySource(
            UUID sourceNpcUuid
    ) {
        return captureEvents.findRowsBySource(sourceNpcUuid, 2);
    }

    @Override
    public List<BondedCompanionCaptureEvidence> listUnpublishedCaptureEvidence(
            int limit
    ) {
        return captureEvents.listUnpublished(limit);
    }

    @Override
    public boolean markCaptureEvidencePublished(
            BondedCompanionCaptureEvidence evidence,
            long publishedAtMs
    ) {
        return captureEvents.markPublished(evidence, publishedAtMs);
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

    @Override public Optional<BondedCompanionRecord.Profile> findProfile(
            UUID ownerUuid, String profileId) {
        return read(store -> store.findProfile(ownerUuid, profileId)
                .map(mapper::toDomain));
    }

    @Override
    public BondedCompanionStoreResult<BondedCompanionRecord.Profile>
            deleteProfile(
                    UUID ownerUuid, String rosterId, String profileId,
                    long expectedRevision
            ) {
        return deletions.delete(ownerUuid, rosterId, profileId,
                expectedRevision);
    }

    @Override
    public Optional<BondedCompanionStoreResult<BondedCompanionRecord.Profile>>
            findProfileOperationByIdentity(BondedCompanionOperationProbe operation) {
        Objects.requireNonNull(operation, "operation");
        return operations.find(operation,
                SqliteBondedCompanionProfileRow.class, mapper::toDomain);
    }

    @Override
    public Optional<BondedCompanionStoreResult<BondedCompanionRecord.Profile>>
            findProfileOperationByExactRequest(
                    BondedCompanionOperation operation) {
        Objects.requireNonNull(operation, "operation");
        return operations.find(operation,
                SqliteBondedCompanionProfileRow.class, mapper::toDomain);
    }

    @Override
    public boolean markProfileOperationPaymentSettled(
            BondedCompanionOperationProbe operation,
            boolean terminalApplied,
            long retainedUntilMs
    ) {
        Objects.requireNonNull(operation, "operation");
        if (retainedUntilMs == 0L || retainedUntilMs == Long.MAX_VALUE) {
            throw new IllegalArgumentException("retainedUntilMs is required");
        }
        return integerWrite(store -> store.retention().markProfileOperationPaymentSettled(
                operation, terminalApplied, retainedUntilMs)) == 1;
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
        return mutate(operation, expectedRevision,
                SqliteBondedCompanionProfileRow.class,
                store -> store.reviveProfile(operation.ownerUuid(), operation.rosterId(),
                        profileId, expectedRevision, updatedAtMs), mapper::toDomain);
    }

    @Override public Optional<BondedCompanionRecord.ExtensionData>
            findExtensionData(UUID ownerUuid, String rosterId,
                              String profileId, String namespace) {
        return read(store -> store.findExtensionData(
                ownerUuid, rosterId, profileId, namespace).map(mapper::toDomain));
    }

    @Override public List<BondedCompanionRecord.ExtensionData> listExtensionData(
            UUID ownerUuid, String rosterId, String profileId) {
        return read(store -> store.listExtensionData(
                        ownerUuid, rosterId, profileId).stream()
                .map(mapper::toDomain).toList());
    }

    @Override public BondedCompanionStoreResult<BondedCompanionRecord.ExtensionData>
            compareAndSetExtensionData(
                    BondedCompanionOperation operation,
                    BondedCompanionRecord.ExtensionData extension,
                    long expectedRevision) {
        requireScope(operation, operation.ownerUuid(), operation.rosterId(),
                extension.profileId());
        Long claimedRevision = expectedRevision < 0L
                ? null : expectedRevision;
        return mutate(operation, claimedRevision,
                SqliteBondedCompanionExtensionDataRow.class,
                store -> store.compareAndSetExtensionData(
                        operation.ownerUuid(), operation.rosterId(),
                        mapper.toRow(extension), expectedRevision), mapper::toDomain);
    }

    @Override public List<BondedCompanionRecord.Lease> findExpiredLeases(
            long nowMs, int limit) {
        return read(store -> store.findExpiredLeases(nowMs, limit).stream()
                .map(mapper::toDomain).toList());
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

    @Override
    public BondedCompanionStoreDiagnostics diagnostics() {
        try (Connection connection = connections.openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                       SUM(CASE WHEN state = 'STORED' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state = 'ACTIVE' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN state = 'DEAD' THEN 1 ELSE 0 END),
                       (SELECT COUNT(*) FROM bonded_companion_lease),
                       (SELECT COUNT(*) FROM bonded_companion_cleanup
                         WHERE cleanup_state = 'PENDING')
                     FROM bonded_companion_profile
                     """);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) {
                throw new SQLException("bonded_diagnostic_row_missing");
            }
            return new BondedCompanionStoreDiagnostics(
                    row.getLong(1), row.getLong(2), row.getLong(3),
                    row.getLong(4), row.getLong(5)
            );
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "bonded-diagnostic-read-failed", failure
            );
        }
    }

    private <R, D> BondedCompanionStoreResult<D> mutate(
            BondedCompanionOperation operation, Class<R> storedType,
            SqliteBondedCompanionOperationExecutor.Mutation<R> mutation,
            SqliteBondedCompanionOperationExecutor.Translation<R, D> translation) {
        return operations.mutate(
                operation, storedType, mutation, translation);
    }

    private <R, D> BondedCompanionStoreResult<D> mutate(
            BondedCompanionOperation operation, Long expectedRevision,
            Class<R> storedType,
            SqliteBondedCompanionOperationExecutor.Mutation<R> mutation,
            SqliteBondedCompanionOperationExecutor.Translation<R, D> translation) {
        return operations.mutate(operation, expectedRevision, storedType,
                mutation, translation);
    }

    private <R, D> BondedCompanionStoreResult<D> mutate(
            BondedCompanionOperation operation, Long expectedRevision,
            Class<R> storedType,
            SqliteBondedCompanionOperationExecutor.Mutation<R> mutation,
            SqliteBondedCompanionOperationExecutor.Translation<R, D> translation,
            BondedCompanionCaptureEvidence captureEvidence) {
        return operations.mutate(operation, expectedRevision,
                storedType, mutation, translation,
                captureEvidence);
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

    @FunctionalInterface private interface Read<T> { T apply(SqliteBondedCompanionStore store); }
    @FunctionalInterface private interface IntegerMutation { int apply(SqliteBondedCompanionStore store); }
}

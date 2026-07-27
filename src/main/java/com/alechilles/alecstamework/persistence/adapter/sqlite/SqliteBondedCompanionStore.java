package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Package-private transaction-local SQLite authority for bonded records.
 *
 * <p>The connection-owning adapter controls commit and rollback. Owner and
 * roster scope is checked before mutation, while revision changes and lease
 * uniqueness are performed on the caller's transaction.</p>
 */
final class SqliteBondedCompanionStore {
    private final Connection connection;
    private final SqliteBondedCompanionRetentionStore retention;
    private final SqliteBondedCompanionExtensionStore extensions;
    private final SqliteBondedCompanionLeaseStore leases;
    private final SqliteBondedCompanionProfileReader profiles;

    SqliteBondedCompanionStore(@Nonnull Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
        retention = new SqliteBondedCompanionRetentionStore(connection);
        extensions = new SqliteBondedCompanionExtensionStore(connection);
        leases = new SqliteBondedCompanionLeaseStore(connection);
        profiles = new SqliteBondedCompanionProfileReader(connection);
    }

    SqliteBondedCompanionRetentionStore retention() { return retention; }

    /** Stable domain outcomes that never expose SQLite result codes. */
    public enum MutationCode {
        APPLIED,
        IDEMPOTENT_REPLAY,
        NOT_FOUND,
        NOT_OWNER,
        REVISION_CONFLICT,
        INVALID_STATE,
        CONFLICT,
        VALIDATION_FAILED,
        STORAGE_FAILURE
    }

    /** Immutable typed result of one bonded store mutation. */
    public record MutationResult<T>(
            @Nonnull MutationCode code,
            @Nullable T value,
            @Nullable String reason
    ) {
        public MutationResult {
            code = Objects.requireNonNull(code, "code");
            reason = reason == null || reason.isBlank() ? null : reason.trim();
        }

        /** Returns whether this request created a new durable effect. */
        public boolean applied() {
            return code == MutationCode.APPLIED;
        }
    }

    /** Inserts one initially stored profile. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionProfileRow> createProfile(
            @Nonnull SqliteBondedCompanionProfileRow row
    ) {
        Objects.requireNonNull(row, "row");
        if (row.state() != BondedCompanionState.STORED || row.revision() != 0) {
            return result(MutationCode.INVALID_STATE, null,
                    "new-profile-must-be-stored-at-revision-zero");
        }
        return write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bonded_companion_profile(
                        profile_id, owner_uuid, roster_id, family_id, role_id,
                        state, revision, snapshot_json, created_at_ms,
                        updated_at_ms, policy_json, display_name, species,
                        gender, died_at_ms, revive_cooldown_until_ms,
                        revive_count, quarantine_reason, quarantined_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                SqliteBondedCompanionRows.bindProfile(statement, row);
                statement.executeUpdate();
                return applied(row);
            }
        });
    }

    /** Inserts a stored profile if its exact owner/roster/family has capacity. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionProfileRow> createProfile(
            @Nonnull SqliteBondedCompanionProfileRow row,
            int maximumOwned
    ) {
        MutationResult<SqliteBondedCompanionProfileRow> denial =
                familyCapacityDenial(row, maximumOwned);
        return denial == null ? createProfile(row) : denial;
    }

    /** Inserts a stored capture and its exact source cleanup in one transaction. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionProfileRow> createCapturedProfile(
            @Nonnull SqliteBondedCompanionProfileRow profile,
            @Nonnull SqliteBondedCompanionCleanupRow cleanup,
            int maximumOwned
    ) {
        MutationResult<SqliteBondedCompanionProfileRow> denial =
                familyCapacityDenial(profile, maximumOwned);
        if (denial != null) return denial;
        MutationResult<SqliteBondedCompanionProfileRow> created =
                createProfile(profile);
        if (!created.applied()) return created;
        MutationResult<SqliteBondedCompanionCleanupRow> queued =
                enqueueCleanup(profile.ownerUuid(), profile.rosterId(), cleanup);
        return queued.applied() ? created : result(
                MutationCode.STORAGE_FAILURE, null,
                "bonded-capture-cleanup-not-enqueued");
    }

    private MutationResult<SqliteBondedCompanionProfileRow>
            familyCapacityDenial(
                    SqliteBondedCompanionProfileRow profile,
                    int maximumOwned
            ) {
        if (maximumOwned < 0) {
            return result(MutationCode.VALIDATION_FAILED, null,
                    "bonded-capacity-invalid");
        }
        if (maximumOwned == 0) return null;
        long owned = profiles.countFamily(
                profile.ownerUuid(), profile.rosterId(), profile.familyId());
        return owned >= maximumOwned
                ? result(MutationCode.CONFLICT, null,
                        "bonded-family-capacity-reached")
                : null;
    }

    /** Lists profiles in deterministic order for exactly one owner roster. */
    @Nonnull
    public List<SqliteBondedCompanionProfileRow> listProfiles(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId
    ) {
        return profiles.list(ownerUuid, rosterId);
    }

    /** Finds a profile only within the supplied owner and roster scope. */
    @Nonnull
    public Optional<SqliteBondedCompanionProfileRow> findProfile(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId
    ) {
        return profiles.find(ownerUuid, rosterId, profileId);
    }

    /** Finds a profile by its stable ID while retaining the owner fence. */
    @Nonnull
    public Optional<SqliteBondedCompanionProfileRow> findProfile(
            @Nonnull UUID ownerUuid,
            @Nonnull String profileId
    ) {
        return profiles.find(ownerUuid, profileId);
    }

    /** Replaces the complete snapshot under an optimistic profile revision. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionProfileRow> updateSnapshot(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision,
            @Nonnull String snapshotJson,
            long updatedAtMs
    ) {
        if (!SqliteBondedJson.isSnapshotEnvelope(snapshotJson)) {
            return result(MutationCode.VALIDATION_FAILED, null,
                    "complete-snapshot-required");
        }
        String snapshot = snapshotJson.trim();
        return write(connection -> {
            Scope scope = scope(connection, profileId, ownerUuid, rosterId);
            MutationResult<SqliteBondedCompanionProfileRow> denied =
                    denied(scope);
            if (denied != null) {
                return denied;
            }
            SqliteBondedCompanionProfileRow current = profile(connection, profileId);
            if (current.revision() != expectedRevision) {
                return result(MutationCode.REVISION_CONFLICT, current,
                        "profile-revision-conflict");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bonded_companion_profile
                    SET snapshot_json = ?, revision = revision + 1,
                        updated_at_ms = ?
                    WHERE profile_id = ? AND revision = ?
                    """)) {
                statement.setString(1, snapshot);
                statement.setLong(2, updatedAtMs);
                statement.setString(3, current.profileId());
                statement.setLong(4, expectedRevision);
                if (statement.executeUpdate() != 1) {
                    return result(MutationCode.REVISION_CONFLICT, null,
                            "profile-revision-conflict");
                }
            }
            return applied(profile(connection, profileId));
        });
    }

    /** Atomically changes STORED to ACTIVE and inserts the sole exact lease. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionLeaseRow> acquireLease(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            long expectedRevision,
            @Nonnull SqliteBondedCompanionLeaseRow lease
    ) {
        Objects.requireNonNull(lease, "lease");
        return write(ignored -> leases.acquire(
                ownerUuid, requireText(rosterId, "rosterId"),
                expectedRevision, lease
        ));
    }

    /** Atomically removes the exact lease and returns ACTIVE to STORED. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionProfileRow> releaseLease(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            @Nonnull String leaseToken,
            long expectedRevision,
            long updatedAtMs
    ) {
        return write(ignored -> leases.release(
                ownerUuid, requireText(rosterId, "rosterId"),
                requireText(profileId, "profileId"),
                requireText(leaseToken, "leaseToken"),
                expectedRevision, updatedAtMs
        ));
    }

    /** Changes DEAD to STORED while preserving signed Hytale timestamps. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionProfileRow> reviveProfile(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision,
            long updatedAtMs
    ) {
        return write(connection -> {
            Scope scope = scope(connection, profileId, ownerUuid, rosterId);
            MutationResult<SqliteBondedCompanionProfileRow> denied = denied(scope);
            if (denied != null) {
                return denied;
            }
            SqliteBondedCompanionProfileRow current = profile(connection, profileId);
            if (current.revision() != expectedRevision) {
                return result(MutationCode.REVISION_CONFLICT, current,
                        "profile-revision-conflict");
            }
            if (current.state() != BondedCompanionState.DEAD) {
                return result(MutationCode.INVALID_STATE, current,
                        "revive-requires-dead-profile");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE bonded_companion_profile
                    SET state = 'STORED', revision = revision + 1,
                        updated_at_ms = ?, died_at_ms = NULL,
                        revive_count = revive_count + 1
                    WHERE profile_id = ? AND revision = ? AND state = 'DEAD'
                    """)) {
                statement.setLong(1, updatedAtMs);
                statement.setString(2, current.profileId());
                statement.setLong(3, expectedRevision);
                if (statement.executeUpdate() != 1) {
                    return result(MutationCode.REVISION_CONFLICT, current,
                            "profile-revision-conflict");
                }
            }
            return applied(profile(connection, profileId));
        });
    }

    /** Finds one namespaced payload in the exact owner roster scope. */
    @Nonnull
    public Optional<SqliteBondedCompanionExtensionDataRow> findExtensionData(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            @Nonnull String namespace
    ) {
        try {
            return extensions.find(
                    ownerUuid,
                    requireText(rosterId, "rosterId"),
                    requireText(profileId, "profileId"),
                    requireText(namespace, "namespace")
            );
        } catch (SQLException failure) {
            throw storageFailure("find-bonded-extension", failure);
        }
    }

    /** Lists namespaced payloads in the exact owner roster scope. */
    @Nonnull
    public List<SqliteBondedCompanionExtensionDataRow> listExtensionData(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId
    ) {
        try {
            return extensions.list(
                    ownerUuid,
                    requireText(rosterId, "rosterId"),
                    requireText(profileId, "profileId")
            );
        } catch (SQLException failure) {
            throw storageFailure("list-bonded-extensions", failure);
        }
    }

    /** Inserts or updates extension data under compare-and-set semantics. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionExtensionDataRow>
            compareAndSetExtensionData(
                    @Nonnull UUID ownerUuid,
                    @Nonnull String rosterId,
                    @Nonnull SqliteBondedCompanionExtensionDataRow row,
                    long expectedRevision
            ) {
        Objects.requireNonNull(row, "row");
        return write(ignored -> extensions.compareAndSet(
                ownerUuid,
                requireText(rosterId, "rosterId"),
                row,
                expectedRevision
        ));
    }

    /** Returns finite leases whose signed expiry is at or before the supplied time. */
    @Nonnull
    public List<SqliteBondedCompanionLeaseRow> findExpiredLeases(
            long nowMs,
            int limit
    ) {
        try {
            return leases.expired(nowMs, limit);
        } catch (SQLException failure) {
            throw storageFailure("find-expired-bonded-leases", failure);
        }
    }

    /** Enqueues one owner-scoped physical cleanup intent. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionCleanupRow> enqueueCleanup(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull SqliteBondedCompanionCleanupRow row
    ) {
        return retention.enqueueCleanup(ownerUuid, rosterId, row);
    }

    /** Lists bounded cleanup intents in the exact owner roster scope. */
    @Nonnull
    public List<SqliteBondedCompanionCleanupRow> listCleanup(
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            int limit
    ) {
        return retention.listCleanup(ownerUuid, rosterId, limit);
    }

    /** Deletes at most limit completed/abandoned cleanup rows past retention. */
    public int pruneCleanup(long nowMs, int limit) {
        return retention.pruneCleanup(nowMs, limit);
    }

    /** Records an idempotency key or returns the exact prior record on replay. */
    @Nonnull
    public MutationResult<SqliteBondedCompanionOperationRow> recordOperation(
            @Nonnull SqliteBondedCompanionOperationRow row
    ) {
        return retention.recordOperation(row);
    }

    /** Deletes at most limit expired idempotency records. */
    public int pruneOperations(long nowMs, int limit) {
        return retention.pruneOperations(nowMs, limit);
    }

    private <T> MutationResult<T> write(SqlWrite<T> work) {
        try {
            return work.execute(connection);
        } catch (SQLException failure) {
            return result(isConstraint(failure)
                            ? MutationCode.CONFLICT : MutationCode.STORAGE_FAILURE,
                    null, "bonded-storage-failure");
        }
    }

    private Scope scope(
            Connection connection,
            String profileId,
            UUID ownerUuid,
            String rosterId
    ) throws SQLException {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid, roster_id FROM bonded_companion_profile
                WHERE profile_id = ?
                """)) {
            statement.setString(1, requireText(profileId, "profileId"));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Scope.NOT_FOUND;
                }
                return ownerUuid.toString().equals(row.getString("owner_uuid"))
                        && requireText(rosterId, "rosterId")
                        .equals(row.getString("roster_id"))
                        ? Scope.ALLOWED : Scope.NOT_OWNER;
            }
        }
    }

    private SqliteBondedCompanionProfileRow profile(
            Connection connection,
            String profileId
    ) throws SQLException {
        return profiles.require(profileId);
    }

    private <T> MutationResult<T> denied(Scope scope) {
        return switch (scope) {
            case ALLOWED -> null;
            case NOT_FOUND -> result(MutationCode.NOT_FOUND, null,
                    "profile-not-found");
            case NOT_OWNER -> result(MutationCode.NOT_OWNER, null,
                    "profile-scope-mismatch");
        };
    }

    private <T> MutationResult<T> applied(T value) {
        return result(MutationCode.APPLIED, value, null);
    }

    private <T> MutationResult<T> result(
            MutationCode code,
            T value,
            String reason
    ) {
        return new MutationResult<>(code, value, reason);
    }

    private boolean isConstraint(SQLException failure) {
        String message = failure.getMessage();
        return message != null && message.toUpperCase(Locale.ROOT)
                .contains("CONSTRAINT");
    }

    private IllegalStateException storageFailure(String operation,
                                                 SQLException failure) {
        return new IllegalStateException(operation, failure);
    }

    private void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private enum Scope { ALLOWED, NOT_FOUND, NOT_OWNER }

    @FunctionalInterface
    private interface SqlWrite<T> {
        MutationResult<T> execute(Connection connection) throws SQLException;
    }

}

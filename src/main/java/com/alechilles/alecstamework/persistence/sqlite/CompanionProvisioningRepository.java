package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Persists exactly-one profile provisioning across dormant creation and optional projection. */
public final class CompanionProvisioningRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CompanionProvisioningRepository(@Nonnull SqliteConnectionManager connectionManager,
                                           @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> createAsync(
            @Nonnull CompanionProvisioningOperationRecord operation) {
        if (operation.state() != CompanionProvisioningOperationRecord.State.PREPARING_DORMANT
                || operation.canonicalProfileId() != null) {
            throw new IllegalArgumentException("New provisioning operations must begin PREPARING_DORMANT.");
        }
        return writeQueue.submitTracked(
                "companion_provisioning_create",
                connection -> createInTransaction(connection, operation),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> advanceAsync(
            @Nonnull AdvanceMutation mutation) {
        return writeQueue.submitTracked(
                "companion_provisioning_advance",
                connection -> advanceInTransaction(connection, mutation),
                null
        );
    }

    @Nullable
    public CompanionProvisioningOperationRecord find(@Nonnull String operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return find(connection, operationId);
        }
    }

    @Nullable
    public CompanionProvisioningOperationRecord findByCallerKey(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findByCallerKey(connection, callerNamespace, idempotencyKey);
        }
    }

    @Nullable
    public CompanionProvisioningOperationRecord findByCanonicalProfile(
            @Nonnull String profileId) throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT_COLUMNS + " WHERE canonical_profile_id = ? LIMIT 1")) {
            statement.setString(1, requireText(profileId, "profileId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    @Nonnull
    public List<CompanionProvisioningOperationRecord> loadRecoverable() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT_COLUMNS + " WHERE state IN "
                             + "('PREPARING_DORMANT', 'DORMANT_PREPARED', 'DORMANT_APPLYING', "
                             + "'DORMANT_COMMITTED', 'ACTIVE_PREPARED', 'ACTIVE_APPLYING', 'QUARANTINED') "
                             + "ORDER BY created_at_ms, operation_id");
             ResultSet result = statement.executeQuery()) {
            List<CompanionProvisioningOperationRecord> operations = new ArrayList<>();
            while (result.next()) operations.add(read(result));
            return List.copyOf(operations);
        }
    }

    /** Bootstrap snapshot of rows that have durably allocated a canonical profile. */
    @Nonnull
    public List<CompanionProvisioningOperationRecord> loadAuthoritativeProfiles() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT_COLUMNS + " WHERE canonical_profile_id IS NOT NULL "
                             + "AND state NOT IN ('DENIED', 'CANCELED') "
                             + "ORDER BY canonical_profile_id, operation_id");
             ResultSet result = statement.executeQuery()) {
            List<CompanionProvisioningOperationRecord> operations = new ArrayList<>();
            while (result.next()) operations.add(read(result));
            return List.copyOf(operations);
        }
    }

    @Nonnull
    private MutationResult createInTransaction(
            @Nonnull Connection connection,
            @Nonnull CompanionProvisioningOperationRecord requested) throws Exception {
        CompanionProvisioningOperationRecord existing = find(connection, requested.operationId());
        if (existing == null) {
            existing = findByCallerKey(
                    connection, requested.callerNamespace(), requested.idempotencyKey());
        }
        if (existing != null) {
            return sameRequest(existing, requested)
                    ? new MutationResult(Status.IDEMPOTENT, existing, "operation_exists")
                    : new MutationResult(Status.CONFLICT, existing, "idempotency_key_in_use");
        }
        insert(connection, requested);
        return new MutationResult(Status.CREATED, requested, null);
    }

    /** Transaction seam used by the provisioning coordinator's population/profile commit. */
    MutationResult advanceInTransaction(@Nonnull Connection connection,
                                        @Nonnull AdvanceMutation mutation) throws Exception {
        CompanionProvisioningOperationRecord existing = find(connection, mutation.operationId());
        if (existing == null) {
            return new MutationResult(Status.NOT_FOUND, null, "operation_not_found");
        }
        if (existing.state() == mutation.next()) {
            return compatibleReplay(existing, mutation)
                    ? new MutationResult(Status.IDEMPOTENT, existing, "already_advanced")
                    : new MutationResult(Status.CONFLICT, existing, "terminal_result_changed");
        }
        if (existing.state() != mutation.expected()) {
            return new MutationResult(Status.INVALID_STATE, existing, "operation_state_changed");
        }
        if (!mutation.expected().canTransitionTo(mutation.next())) {
            throw new IllegalArgumentException("Invalid provisioning transition: "
                    + mutation.expected() + " -> " + mutation.next());
        }
        String conflict = immutableIdentityConflict(existing, mutation);
        if (conflict != null) {
            return new MutationResult(Status.CONFLICT, existing, conflict);
        }
        update(connection, existing, mutation);
        return new MutationResult(statusFor(mutation.next()), find(connection, mutation.operationId()), null);
    }

    private void insert(Connection connection,
                        CompanionProvisioningOperationRecord operation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_provisioning_operations (
                    operation_id, caller_namespace, idempotency_key, correlation_id,
                    owner_uuid, target_role_id, requested_disposition, ownership_world_name,
                    destination_context_json, initial_profile_json, expected_policy_revision,
                    provisional_profile_id, canonical_profile_id, state,
                    dormant_population_operation_id, active_population_operation_id,
                    result_code, projection_reason, recovery_status,
                    created_at_ms, updated_at_ms, completed_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 1;
            statement.setString(index++, operation.operationId());
            statement.setString(index++, operation.callerNamespace());
            statement.setString(index++, operation.idempotencyKey());
            setText(statement, index++, operation.correlationId());
            statement.setString(index++, operation.ownerUuid().toString());
            statement.setString(index++, operation.targetRoleId());
            statement.setString(index++, operation.requestedDisposition().name());
            setText(statement, index++, operation.ownershipWorldName());
            setText(statement, index++, operation.destinationContextJson());
            setText(statement, index++, operation.initialProfileJson());
            setLong(statement, index++, operation.expectedPolicyRevision());
            statement.setString(index++, operation.provisionalProfileId());
            setText(statement, index++, operation.canonicalProfileId());
            statement.setString(index++, operation.state().name());
            setText(statement, index++, operation.dormantPopulationOperationId());
            setText(statement, index++, operation.activePopulationOperationId());
            setText(statement, index++, operation.resultCode());
            setText(statement, index++, operation.projectionReason());
            statement.setString(index++, operation.recoveryStatus());
            statement.setLong(index++, operation.createdAtMs());
            statement.setLong(index++, operation.updatedAtMs());
            statement.setLong(index, operation.completedAtMs());
            statement.executeUpdate();
        }
    }

    private void update(Connection connection,
                        CompanionProvisioningOperationRecord existing,
                        AdvanceMutation mutation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_provisioning_operations
                SET state = ?, canonical_profile_id = COALESCE(canonical_profile_id, ?),
                    dormant_population_operation_id = COALESCE(dormant_population_operation_id, ?),
                    active_population_operation_id = COALESCE(active_population_operation_id, ?),
                    result_code = COALESCE(?, result_code),
                    projection_reason = COALESCE(?, projection_reason),
                    recovery_status = COALESCE(?, recovery_status), updated_at_ms = ?,
                    completed_at_ms = CASE WHEN ? IN
                        ('COMMITTED', 'PARTIAL_DORMANT', 'DENIED', 'CANCELED') THEN ? ELSE 0 END
                WHERE operation_id = ? AND state = ?
                """)) {
            statement.setString(1, mutation.next().name());
            setText(statement, 2, mutation.canonicalProfileId());
            setText(statement, 3, mutation.dormantPopulationOperationId());
            setText(statement, 4, mutation.activePopulationOperationId());
            setText(statement, 5, mutation.resultCode());
            setText(statement, 6, mutation.projectionReason());
            setText(statement, 7, mutation.recoveryStatus());
            statement.setLong(8, mutation.updatedAtMs());
            statement.setString(9, mutation.next().name());
            statement.setLong(10, mutation.updatedAtMs());
            statement.setString(11, existing.operationId());
            statement.setString(12, mutation.expected().name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Provisioning operation changed during transition.");
            }
        }
    }

    @Nullable
    private CompanionProvisioningOperationRecord find(Connection connection,
                                                      String operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE operation_id = ? LIMIT 1")) {
            statement.setString(1, requireText(operationId, "operationId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    @Nullable
    private CompanionProvisioningOperationRecord findByCallerKey(
            Connection connection, String callerNamespace, String idempotencyKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ? LIMIT 1")) {
            statement.setString(1, requireText(callerNamespace, "callerNamespace"));
            statement.setString(2, requireText(idempotencyKey, "idempotencyKey"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private CompanionProvisioningOperationRecord read(ResultSet result) throws Exception {
        return new CompanionProvisioningOperationRecord(
                result.getString("operation_id"), result.getString("caller_namespace"),
                result.getString("idempotency_key"), result.getString("correlation_id"),
                UUID.fromString(result.getString("owner_uuid")), result.getString("target_role_id"),
                CompanionProvisioningOperationRecord.RequestedDisposition.valueOf(
                        result.getString("requested_disposition")),
                result.getString("ownership_world_name"), result.getString("destination_context_json"),
                result.getString("initial_profile_json"), nullableLong(result, "expected_policy_revision"),
                result.getString("provisional_profile_id"), result.getString("canonical_profile_id"),
                CompanionProvisioningOperationRecord.State.valueOf(result.getString("state")),
                result.getString("dormant_population_operation_id"),
                result.getString("active_population_operation_id"), result.getString("result_code"),
                result.getString("projection_reason"), result.getString("recovery_status"),
                result.getLong("created_at_ms"), result.getLong("updated_at_ms"),
                result.getLong("completed_at_ms"));
    }

    private boolean sameRequest(CompanionProvisioningOperationRecord left,
                                CompanionProvisioningOperationRecord right) {
        return left.ownerUuid().equals(right.ownerUuid())
                && left.targetRoleId().equals(right.targetRoleId())
                && left.requestedDisposition() == right.requestedDisposition()
                && left.provisionalProfileId().equals(right.provisionalProfileId())
                && Objects.equals(left.ownershipWorldName(), right.ownershipWorldName())
                && sameJson(left.destinationContextJson(), right.destinationContextJson())
                && sameJson(left.initialProfileJson(), right.initialProfileJson())
                && Objects.equals(left.expectedPolicyRevision(), right.expectedPolicyRevision());
    }

    private boolean sameJson(@Nullable String left, @Nullable String right) {
        if (Objects.equals(left, right)) return true;
        if (left == null || right == null) return false;
        try {
            return JsonParser.parseString(left).equals(JsonParser.parseString(right));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean compatibleReplay(CompanionProvisioningOperationRecord existing,
                                     AdvanceMutation mutation) {
        return (mutation.canonicalProfileId() == null
                || Objects.equals(existing.canonicalProfileId(), mutation.canonicalProfileId()))
                && (mutation.resultCode() == null
                || Objects.equals(existing.resultCode(), mutation.resultCode()));
    }

    @Nullable
    private String immutableIdentityConflict(CompanionProvisioningOperationRecord existing,
                                             AdvanceMutation mutation) {
        if (existing.canonicalProfileId() != null && mutation.canonicalProfileId() != null
                && !existing.canonicalProfileId().equals(mutation.canonicalProfileId())) {
            return "canonical_profile_changed";
        }
        if (existing.dormantPopulationOperationId() != null
                && mutation.dormantPopulationOperationId() != null
                && !existing.dormantPopulationOperationId().equals(mutation.dormantPopulationOperationId())) {
            return "dormant_population_operation_changed";
        }
        if (existing.activePopulationOperationId() != null
                && mutation.activePopulationOperationId() != null
                && !existing.activePopulationOperationId().equals(mutation.activePopulationOperationId())) {
            return "active_population_operation_changed";
        }
        return null;
    }

    private Status statusFor(CompanionProvisioningOperationRecord.State state) {
        return switch (state) {
            case COMMITTED -> Status.COMMITTED;
            case PARTIAL_DORMANT -> Status.PARTIAL_DORMANT;
            case DENIED -> Status.DENIED;
            case CANCELED -> Status.CANCELED;
            case QUARANTINED -> Status.QUARANTINED;
            default -> Status.ADVANCED;
        };
    }

    private static void setText(PreparedStatement statement, int index, @Nullable String value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setLong(PreparedStatement statement, int index, @Nullable Long value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    @Nullable
    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }

    public enum Status {
        CREATED,
        ADVANCED,
        COMMITTED,
        PARTIAL_DORMANT,
        DENIED,
        CANCELED,
        QUARANTINED,
        IDEMPOTENT,
        NOT_FOUND,
        INVALID_STATE,
        CONFLICT
    }

    public record MutationResult(@Nonnull Status status,
                                 @Nullable CompanionProvisioningOperationRecord operation,
                                 @Nullable String reason) {
    }

    public record AdvanceMutation(
            @Nonnull String operationId,
            @Nonnull CompanionProvisioningOperationRecord.State expected,
            @Nonnull CompanionProvisioningOperationRecord.State next,
            @Nullable String canonicalProfileId,
            @Nullable String dormantPopulationOperationId,
            @Nullable String activePopulationOperationId,
            @Nullable String resultCode,
            @Nullable String projectionReason,
            @Nullable String recoveryStatus,
            long updatedAtMs
    ) {
        public AdvanceMutation {
            operationId = requireText(operationId, "operationId");
            expected = Objects.requireNonNull(expected, "expected");
            next = Objects.requireNonNull(next, "next");
        }
    }

    private static final String SELECT_COLUMNS = """
            SELECT operation_id, caller_namespace, idempotency_key, correlation_id,
                   owner_uuid, target_role_id, requested_disposition, ownership_world_name,
                   destination_context_json, initial_profile_json, expected_policy_revision,
                   provisional_profile_id, canonical_profile_id, state,
                   dormant_population_operation_id, active_population_operation_id,
                   result_code, projection_reason, recovery_status,
                   created_at_ms, updated_at_ms, completed_at_ms
            FROM companion_provisioning_operations
            """;
}

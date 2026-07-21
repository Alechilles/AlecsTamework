package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.CLASSIFICATION_COLUMNS;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.OPERATION_COLUMNS;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.findActiveOperation;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.insertEvidence;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.insertOperation;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.readClassification;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.readOperation;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.replaceAssignments;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.updateEvidenceState;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.updateOperation;
import static com.alechilles.alecstamework.persistence.sqlite.PopulationGroupSqlStore.upsertClassification;

/** Persists canonical group assignments and the exact reservation evidence used by admissions. */
public final class PopulationGroupRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public PopulationGroupRepository(@Nonnull SqliteConnectionManager connectionManager,
                                     @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ClassificationResult> replaceClassificationAsync(
            @Nonnull ClassificationMutation mutation) {
        return writeQueue.submitTracked(
                "population_group_classification_replace",
                connection -> replaceClassificationInTransaction(connection, mutation),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<OperationResult> prepareOperationAsync(
            @Nonnull PopulationGroupOperationRecord operation,
            @Nonnull List<PopulationGroupCountEvidenceRecord> evidence) {
        if (operation.state() != PopulationGroupOperationRecord.State.PREPARED) {
            throw new IllegalArgumentException("Group operations must begin PREPARED.");
        }
        List<PopulationGroupCountEvidenceRecord> immutableEvidence = List.copyOf(evidence);
        validateEvidence(operation, immutableEvidence);
        return writeQueue.submitTracked(
                "population_group_operation_prepare",
                connection -> prepareOperationInTransaction(connection, operation, immutableEvidence),
                null
        );
    }

    /**
     * Atomically checks every positive group delta against authoritative committed and reserved
     * counts, then persists the complete reservation. A denied request writes nothing.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ReservationResult> reserveOperationAsync(
            @Nonnull PopulationGroupOperationRecord operation,
            @Nonnull List<ReservationEvidence> requestedEvidence) {
        List<ReservationEvidence> immutableEvidence = List.copyOf(requestedEvidence);
        validateReservationRequest(operation, immutableEvidence);
        return writeQueue.submitTracked(
                "population_group_operation_reserve",
                connection -> reserveOperationInTransaction(connection, operation, immutableEvidence),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<OperationResult> advanceOperationAsync(
            @Nonnull String operationId,
            @Nonnull PopulationGroupOperationRecord.State expected,
            @Nonnull PopulationGroupOperationRecord.State next,
            @Nullable String reason,
            long nowMs) {
        if (!expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("Invalid group operation transition: " + expected + " -> " + next);
        }
        return writeQueue.submitTracked(
                "population_group_operation_advance",
                connection -> advanceOperationInTransaction(
                        connection, operationId, expected, next, reason, nowMs),
                null
        );
    }

    @Nullable
    public PopulationGroupClassificationRecord findClassification(@Nonnull String profileId)
            throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findClassification(connection, profileId);
        }
    }

    @Nonnull
    public List<PopulationGroupClassificationRecord> loadAllClassifications() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     CLASSIFICATION_COLUMNS + " ORDER BY profile_id");
             ResultSet result = statement.executeQuery()) {
            List<PopulationGroupClassificationRecord> classifications = new ArrayList<>();
            while (result.next()) {
                classifications.add(readClassification(result));
            }
            return List.copyOf(classifications);
        }
    }

    @Nullable
    public PopulationGroupOperationRecord findOperation(@Nonnull String operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findOperation(connection, operationId);
        }
    }

    @Nonnull
    public List<PopulationGroupOperationRecord> loadRecoverableOperations() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     OPERATION_COLUMNS + " WHERE state IN "
                             + "('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING', 'QUARANTINED') "
                             + "ORDER BY created_at_ms, operation_id");
             ResultSet result = statement.executeQuery()) {
            List<PopulationGroupOperationRecord> operations = new ArrayList<>();
            while (result.next()) operations.add(readOperation(result));
            return List.copyOf(operations);
        }
    }

    /** Loads every durable attempt correlated to one higher-level population operation. */
    @Nonnull
    public List<PopulationGroupOperationRecord> loadOperationsByPopulationOperationId(
            @Nonnull String populationOperationId) throws Exception {
        String normalized = requireText(populationOperationId, "populationOperationId");
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     OPERATION_COLUMNS + " WHERE population_operation_id = ? "
                             + "ORDER BY created_at_ms, operation_id")) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                List<PopulationGroupOperationRecord> operations = new ArrayList<>();
                while (result.next()) operations.add(readOperation(result));
                return List.copyOf(operations);
            }
        }
    }

    @Nonnull
    public List<PopulationGroupCountEvidenceRecord> loadCountEvidence(@Nonnull String operationId)
            throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return loadCountEvidence(connection, operationId);
        }
    }

    /** Reads authoritative committed counts plus positive pending reservations for one scope. */
    @Nonnull
    public Counts count(@Nonnull UUID ownerUuid,
                        @Nonnull String groupId,
                        @Nonnull PopulationGroupCountEvidenceRecord.ScopeKind scopeKind,
                        @Nullable String scopeWorldName) throws Exception {
        String world = normalizedWorld(scopeKind, scopeWorldName);
        try (Connection connection = connectionManager.openConnection()) {
            int[] committed = committedCounts(connection, ownerUuid, groupId, scopeKind, world);
            int[] pending = pendingCounts(connection, ownerUuid, groupId, scopeKind, world);
            return new Counts(committed[0], committed[1], pending[0], pending[1]);
        }
    }

    /**
     * Package transaction seam for the unified population commit. The caller must update canonical
     * population state in the same transaction before publishing the resulting runtime index.
     */
    ClassificationResult replaceClassificationInTransaction(
            @Nonnull Connection connection,
            @Nonnull ClassificationMutation mutation) throws Exception {
        PopulationGroupClassificationRecord replacement = mutation.replacement();
        PopulationGroupClassificationRecord existing = findClassification(
                connection, replacement.profileId());
        if (!revisionMatches(existing, mutation.expectedRevision())) {
            return new ClassificationResult(
                    Status.CONFLICT, existing, "classification_revision_changed");
        }
        if (existing != null && sameClassification(existing, replacement)) {
            return new ClassificationResult(Status.IDEMPOTENT, existing, "classification_unchanged");
        }
        if (existing != null
                && replacement.classificationRevision() < existing.classificationRevision()) {
            return new ClassificationResult(Status.CONFLICT, existing, "classification_revision_regressed");
        }
        upsertClassification(connection, replacement);
        replaceAssignments(connection, replacement);
        return new ClassificationResult(Status.APPLIED, replacement, null);
    }

    /** Transaction seam used by the unified owner/group admission coordinator. */
    @Nonnull
    ReservationResult reserveOperationInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationGroupOperationRecord operation,
            @Nonnull List<ReservationEvidence> requestedEvidence) throws Exception {
        PopulationGroupOperationRecord existing = findOperation(connection, operation.operationId());
        if (existing != null) {
            List<PopulationGroupCountEvidenceRecord> persisted =
                    loadCountEvidence(connection, operation.operationId());
            if (!sameOperation(existing, operation)
                    || !sameReservationEvidence(persisted, requestedEvidence)) {
                return new ReservationResult(Status.CONFLICT, existing, List.of(),
                        "operation_id_in_use");
            }
            return new ReservationResult(
                    Status.IDEMPOTENT,
                    existing,
                    persisted,
                    "operation_exists"
            );
        }
        PopulationGroupOperationRecord active = findActiveOperation(connection, operation.profileId());
        if (active != null) {
            return new ReservationResult(Status.CONFLICT, active, List.of(),
                    "profile_operation_in_flight");
        }

        List<PopulationGroupCountEvidenceRecord> frozen = new ArrayList<>();
        for (ReservationEvidence requested : requestedEvidence) {
            String world = normalizedWorld(requested.scopeKind(), requested.scopeWorldName());
            int[] committed = committedCounts(
                    connection, requested.ownerUuid(), requested.groupId(), requested.scopeKind(), world);
            int[] pending = pendingCounts(
                    connection, requested.ownerUuid(), requested.groupId(), requested.scopeKind(), world);
            if (exceeds(committed[0], pending[0], requested.ownedDelta(), requested.maxOwned())) {
                return new ReservationResult(Status.DENIED, null, List.of(),
                        "population-group-owned-limit");
            }
            if (exceeds(committed[1], pending[1], requested.activeDelta(), requested.maxActive())) {
                return new ReservationResult(Status.DENIED, null, List.of(),
                        "population-group-active-limit");
            }
            frozen.add(new PopulationGroupCountEvidenceRecord(
                    operation.operationId(), requested.ownerUuid(), requested.groupId(),
                    requested.scopeKind(), world, committed[0], committed[1], pending[0], pending[1],
                    requested.ownedDelta(), requested.activeDelta(), requested.maxOwned(),
                    requested.maxActive(), requested.policyRevision(),
                    PopulationGroupCountEvidenceRecord.State.RESERVED,
                    operation.createdAtMs(), operation.updatedAtMs()
            ));
        }
        insertOperation(connection, operation);
        for (PopulationGroupCountEvidenceRecord evidence : frozen) insertEvidence(connection, evidence);
        return new ReservationResult(Status.PREPARED, operation, List.copyOf(frozen), null);
    }

    /** Atomically applies frozen classification evidence and moves its journal to APPLIED. */
    OperationResult applyClassificationInTransaction(
            @Nonnull Connection connection,
            @Nonnull String operationId,
            @Nonnull ClassificationMutation classification,
            long nowMs) throws Exception {
        PopulationGroupOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) return new OperationResult(Status.NOT_FOUND, null, "operation_not_found");
        if (operation.state() == PopulationGroupOperationRecord.State.APPLIED) {
            return new OperationResult(Status.IDEMPOTENT, operation, "already_applied");
        }
        if (operation.state() != PopulationGroupOperationRecord.State.APPLYING
                || !operation.profileId().equals(classification.replacement().profileId())
                || operation.classificationRevision()
                    != classification.replacement().classificationRevision()
                || !operation.newGroupIds().equals(classification.replacement().groupIds())) {
            return new OperationResult(Status.CONFLICT, operation, "frozen_classification_mismatch");
        }
        ClassificationResult replaced = replaceClassificationInTransaction(connection, classification);
        if (replaced.status() != Status.APPLIED && replaced.status() != Status.IDEMPOTENT) {
            return new OperationResult(replaced.status(), operation, replaced.reason());
        }
        return advanceOperationInTransaction(connection, operationId,
                PopulationGroupOperationRecord.State.APPLYING,
                PopulationGroupOperationRecord.State.APPLIED, null, nowMs);
    }

    /** Applies the frozen classification and closes its group journal in one outer transaction. */
    @Nonnull
    OperationResult commitClassificationOperationInTransaction(
            @Nonnull Connection connection,
            @Nonnull String operationId,
            @Nonnull ClassificationMutation classification,
            long nowMs) throws Exception {
        PopulationGroupOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) return new OperationResult(Status.NOT_FOUND, null, "operation_not_found");
        if (operation.state() == PopulationGroupOperationRecord.State.COMMITTED) {
            PopulationGroupClassificationRecord existing = findClassification(
                    connection, classification.replacement().profileId());
            return existing != null && sameClassification(existing, classification.replacement())
                    ? new OperationResult(Status.IDEMPOTENT, operation, "already_committed")
                    : new OperationResult(Status.CONFLICT, operation,
                            "committed_classification_mismatch");
        }
        if (operation.state() == PopulationGroupOperationRecord.State.APPLYING) {
            OperationResult applied = applyClassificationInTransaction(
                    connection, operationId, classification, nowMs);
            if (applied.status() != Status.APPLIED && applied.status() != Status.IDEMPOTENT) {
                return applied;
            }
            operation = findOperation(connection, operationId);
        }
        if (operation == null || operation.state() != PopulationGroupOperationRecord.State.APPLIED) {
            return new OperationResult(Status.INVALID_STATE, operation,
                    "operation_not_applied");
        }
        return advanceOperationInTransaction(connection, operationId,
                PopulationGroupOperationRecord.State.APPLIED,
                PopulationGroupOperationRecord.State.COMMITTED, null, nowMs);
    }

    @Nonnull
    private OperationResult prepareOperationInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationGroupOperationRecord operation,
            @Nonnull List<PopulationGroupCountEvidenceRecord> evidence) throws Exception {
        PopulationGroupOperationRecord existing = findOperation(connection, operation.operationId());
        if (existing != null) {
            return sameOperation(existing, operation)
                    ? new OperationResult(Status.IDEMPOTENT, existing, "operation_exists")
                    : new OperationResult(Status.CONFLICT, existing, "operation_id_in_use");
        }
        PopulationGroupOperationRecord active = findActiveOperation(connection, operation.profileId());
        if (active != null) {
            return new OperationResult(Status.CONFLICT, active, "profile_operation_in_flight");
        }
        insertOperation(connection, operation);
        for (PopulationGroupCountEvidenceRecord row : evidence) {
            insertEvidence(connection, row);
        }
        return new OperationResult(Status.PREPARED, operation, null);
    }

    @Nonnull
    OperationResult advanceOperationInTransaction(
            @Nonnull Connection connection,
            @Nonnull String operationId,
            @Nonnull PopulationGroupOperationRecord.State expected,
            @Nonnull PopulationGroupOperationRecord.State next,
            @Nullable String reason,
            long nowMs) throws Exception {
        PopulationGroupOperationRecord existing = findOperation(connection, operationId);
        if (existing == null) return new OperationResult(Status.NOT_FOUND, null, "operation_not_found");
        if (existing.state() == next) {
            return new OperationResult(Status.IDEMPOTENT, existing, "already_advanced");
        }
        if (existing.state() != expected) {
            return new OperationResult(Status.INVALID_STATE, existing, "operation_state_changed");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            updateOperation(connection, operationId, expected, next, reason, nowMs);
            updateEvidenceState(connection, operationId, evidenceState(next), nowMs);
            connection.releaseSavepoint(savepoint);
        } catch (Exception failure) {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            throw failure;
        }
        return new OperationResult(statusFor(next), findOperation(connection, operationId), null);
    }

    private int[] committedCounts(Connection connection, UUID ownerUuid, String groupId,
                                  PopulationGroupCountEvidenceRecord.ScopeKind scopeKind,
                                  @Nullable String world) throws Exception {
        String worldClause = scopeKind == PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL
                ? "" : " AND s.ownership_world_name = ?";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS owned_count,
                       COALESCE(SUM(CASE WHEN s.lifecycle_state IN ('ACTIVE', 'UNLOADED', 'RESTORING')
                           THEN 1 ELSE 0 END), 0) AS active_count
                FROM companion_population_group_assignments a
                INNER JOIN companion_population_state s ON s.profile_id = a.profile_id
                INNER JOIN npc_profiles p ON p.profile_id = a.profile_id
                WHERE p.owner_uuid = ? AND a.group_id = ? AND s.lifecycle_state <> 'RELEASED'
                """ + worldClause)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, requireText(groupId, "groupId"));
            if (world != null) statement.setString(3, world);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new int[] {result.getInt("owned_count"), result.getInt("active_count")};
            }
        }
    }

    private int[] pendingCounts(Connection connection, UUID ownerUuid, String groupId,
                                PopulationGroupCountEvidenceRecord.ScopeKind scopeKind,
                                @Nullable String world) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(MAX(owned_delta, 0)), 0) AS owned_count,
                       COALESCE(SUM(MAX(active_delta, 0)), 0) AS active_count
                FROM companion_population_group_count_evidence
                WHERE owner_uuid = ? AND group_id = ? AND scope_kind = ?
                  AND scope_world_name = ? AND state = 'RESERVED'
                """)) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, requireText(groupId, "groupId"));
            statement.setString(3, scopeKind.name());
            statement.setString(4, world == null ? "" : world);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new int[] {result.getInt("owned_count"), result.getInt("active_count")};
            }
        }
    }

    private PopulationGroupClassificationRecord findClassification(
            Connection connection, String profileId) throws Exception {
        return PopulationGroupSqlStore.findClassification(connection, requireText(profileId, "profileId"));
    }

    private PopulationGroupOperationRecord findOperation(
            Connection connection, String operationId) throws Exception {
        return PopulationGroupSqlStore.findOperation(connection, requireText(operationId, "operationId"));
    }

    private List<PopulationGroupCountEvidenceRecord> loadCountEvidence(
            Connection connection, String operationId) throws Exception {
        return PopulationGroupSqlStore.loadCountEvidence(
                connection, requireText(operationId, "operationId"));
    }

    private void validateEvidence(PopulationGroupOperationRecord operation,
                                  List<PopulationGroupCountEvidenceRecord> evidence) {
        for (PopulationGroupCountEvidenceRecord row : evidence) {
            if (!operation.operationId().equals(row.operationId())
                    || row.state() != PopulationGroupCountEvidenceRecord.State.RESERVED
                    || row.policyRevision() != operation.classificationRevision()) {
                throw new IllegalArgumentException("Count evidence does not match its group operation.");
            }
        }
    }

    private void validateReservationRequest(PopulationGroupOperationRecord operation,
                                            List<ReservationEvidence> evidence) {
        Objects.requireNonNull(operation, "operation");
        if (operation.state() != PopulationGroupOperationRecord.State.PREPARED) {
            throw new IllegalArgumentException("Group operations must begin PREPARED.");
        }
        for (ReservationEvidence row : evidence) {
            if (row.policyRevision() != operation.classificationRevision()) {
                throw new IllegalArgumentException(
                        "Reservation policy revision does not match its group operation.");
            }
        }
    }

    private boolean exceeds(int committed, int pending, int delta, int limit) {
        if (delta <= 0 || limit == 0) return false;
        return (long) committed + pending + delta > limit;
    }

    private boolean revisionMatches(@Nullable PopulationGroupClassificationRecord existing,
                                    @Nullable Long expectedRevision) {
        return existing == null ? expectedRevision == null
                : expectedRevision != null && existing.classificationRevision() == expectedRevision;
    }

    private boolean sameClassification(PopulationGroupClassificationRecord left,
                                       PopulationGroupClassificationRecord right) {
        return Objects.equals(left.roleId(), right.roleId())
                && left.groupIds().equals(right.groupIds())
                && left.classificationRevision() == right.classificationRevision()
                && left.status() == right.status();
    }

    private boolean sameOperation(PopulationGroupOperationRecord left,
                                  PopulationGroupOperationRecord right) {
        return left.profileId().equals(right.profileId())
                && Objects.equals(left.populationOperationId(), right.populationOperationId())
                && left.operationType().equals(right.operationType())
                && left.expectedPopulationRevision() == right.expectedPopulationRevision()
                && left.classificationRevision() == right.classificationRevision()
                && Objects.equals(left.oldOwnerUuid(), right.oldOwnerUuid())
                && Objects.equals(left.newOwnerUuid(), right.newOwnerUuid())
                && Objects.equals(left.oldRoleId(), right.oldRoleId())
                && Objects.equals(left.newRoleId(), right.newRoleId())
                && left.oldGroupIds().equals(right.oldGroupIds())
                && left.newGroupIds().equals(right.newGroupIds())
                && Objects.equals(left.oldLifecycleState(), right.oldLifecycleState())
                && Objects.equals(left.newLifecycleState(), right.newLifecycleState())
                && Objects.equals(left.oldOwnershipWorldName(), right.oldOwnershipWorldName())
                && Objects.equals(left.newOwnershipWorldName(), right.newOwnershipWorldName());
    }

    private boolean sameReservationEvidence(
            List<PopulationGroupCountEvidenceRecord> persisted,
            List<ReservationEvidence> requested) {
        if (persisted.size() != requested.size()) return false;
        List<ReservationEvidence> unmatched = new ArrayList<>(requested);
        for (PopulationGroupCountEvidenceRecord row : persisted) {
            int match = -1;
            for (int index = 0; index < unmatched.size(); index++) {
                ReservationEvidence candidate = unmatched.get(index);
                if (row.ownerUuid().equals(candidate.ownerUuid())
                        && row.groupId().equals(candidate.groupId())
                        && row.scopeKind() == candidate.scopeKind()
                        && Objects.equals(row.scopeWorldName(), normalizedWorld(
                                candidate.scopeKind(), candidate.scopeWorldName()))
                        && row.ownedDelta() == candidate.ownedDelta()
                        && row.activeDelta() == candidate.activeDelta()
                        && row.maxOwned() == candidate.maxOwned()
                        && row.maxActive() == candidate.maxActive()
                        && row.policyRevision() == candidate.policyRevision()) {
                    match = index;
                    break;
                }
            }
            if (match < 0) return false;
            unmatched.remove(match);
        }
        return unmatched.isEmpty();
    }

    private PopulationGroupCountEvidenceRecord.State evidenceState(
            PopulationGroupOperationRecord.State operationState) {
        return switch (operationState) {
            case PREPARED, APPLYING -> PopulationGroupCountEvidenceRecord.State.RESERVED;
            case APPLIED, COMMITTED -> PopulationGroupCountEvidenceRecord.State.APPLIED;
            case CANCELED, FAILED -> PopulationGroupCountEvidenceRecord.State.RELEASED;
            case COMPENSATING, QUARANTINED -> PopulationGroupCountEvidenceRecord.State.QUARANTINED;
        };
    }

    private Status statusFor(PopulationGroupOperationRecord.State state) {
        return switch (state) {
            case PREPARED -> Status.PREPARED;
            case APPLYING -> Status.APPLYING;
            case APPLIED -> Status.APPLIED;
            case COMMITTED -> Status.COMMITTED;
            case CANCELED -> Status.CANCELED;
            case COMPENSATING, QUARANTINED -> Status.QUARANTINED;
            case FAILED -> Status.FAILED;
        };
    }

    private String normalizedWorld(PopulationGroupCountEvidenceRecord.ScopeKind scope,
                                   @Nullable String world) {
        if (scope == PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL) {
            if (world != null && !world.isBlank()) throw new IllegalArgumentException("GLOBAL scope cannot name a world.");
            return null;
        }
        return requireText(world, "scopeWorldName");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }

    public enum Status {
        PREPARED,
        APPLYING,
        APPLIED,
        COMMITTED,
        CANCELED,
        QUARANTINED,
        FAILED,
        IDEMPOTENT,
        NOT_FOUND,
        INVALID_STATE,
        CONFLICT,
        DENIED
    }

    public record ClassificationMutation(@Nullable Long expectedRevision,
                                         @Nonnull PopulationGroupClassificationRecord replacement) {
        public ClassificationMutation {
            if (expectedRevision != null && expectedRevision < 0L) {
                throw new IllegalArgumentException("expectedRevision must be non-negative.");
            }
            replacement = Objects.requireNonNull(replacement, "replacement");
        }
    }

    public record ClassificationResult(@Nonnull Status status,
                                       @Nullable PopulationGroupClassificationRecord classification,
                                       @Nullable String reason) {
    }

    public record OperationResult(@Nonnull Status status,
                                  @Nullable PopulationGroupOperationRecord operation,
                                  @Nullable String reason) {
    }

    public record ReservationEvidence(@Nonnull UUID ownerUuid,
                                      @Nonnull String groupId,
                                      @Nonnull PopulationGroupCountEvidenceRecord.ScopeKind scopeKind,
                                      @Nullable String scopeWorldName,
                                      int ownedDelta,
                                      int activeDelta,
                                      int maxOwned,
                                      int maxActive,
                                      long policyRevision) {
        public ReservationEvidence {
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            groupId = requireText(groupId, "groupId");
            scopeKind = Objects.requireNonNull(scopeKind, "scopeKind");
            if (maxOwned < 0 || maxActive < 0 || policyRevision < 0L) {
                throw new IllegalArgumentException("Limits and policy revision must be non-negative.");
            }
            if (scopeKind == PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL
                    && scopeWorldName != null && !scopeWorldName.isBlank()) {
                throw new IllegalArgumentException("GLOBAL reservation evidence cannot name a world.");
            }
            if (scopeKind == PopulationGroupCountEvidenceRecord.ScopeKind.PER_WORLD
                    && (scopeWorldName == null || scopeWorldName.isBlank())) {
                throw new IllegalArgumentException("PER_WORLD reservation evidence requires a world.");
            }
        }
    }

    public record ReservationResult(@Nonnull Status status,
                                    @Nullable PopulationGroupOperationRecord operation,
                                    @Nonnull List<PopulationGroupCountEvidenceRecord> evidence,
                                    @Nullable String reason) {
        public ReservationResult {
            status = Objects.requireNonNull(status, "status");
            evidence = List.copyOf(evidence);
        }
    }

    public record Counts(int committedOwned,
                         int committedActive,
                         int pendingOwned,
                         int pendingActive) {
    }

}

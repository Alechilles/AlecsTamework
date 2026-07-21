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

import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.OPERATION_COLUMNS;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.readOperation;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselTransitionStore.clearActiveOperation;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselTransitionStore.insertBinding;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselTransitionStore.insertOperation;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselTransitionStore.reserveOperation;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselTransitionStore.updateBindingForApply;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselTransitionStore.updateBindingForClaim;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselTransitionStore.updateOperationApplied;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselTransitionStore.updateOperationState;

/** Owns generation-fenced bonded-vessel binding and transition-journal mutations. */
public final class BondedVesselRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public BondedVesselRepository(@Nonnull SqliteConnectionManager connectionManager,
                                  @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    /** Atomically creates generation one and its first-capture source-finalization journal. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> createInitialBindingAsync(
            @Nonnull BondedVesselBindingRecord binding,
            @Nonnull BondedVesselOperationRecord operation) {
        validateInitial(binding, operation);
        return writeQueue.submitTracked(
                "bonded_vessel_initial_bind",
                connection -> createInitialInTransaction(connection, binding, operation),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> prepareTransitionAsync(
            @Nonnull BondedVesselOperationRecord operation) {
        if (operation.state() != BondedVesselOperationRecord.State.PREPARED
                || operation.action() == BondedVesselOperationRecord.Action.INITIAL_BIND) {
            throw new IllegalArgumentException("Normal vessel transitions must begin PREPARED after generation one.");
        }
        return writeQueue.submitTracked(
                "bonded_vessel_prepare",
                connection -> prepareInTransaction(connection, operation),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> claimForApplyAsync(
            @Nonnull String operationId,
            long nowMs) {
        return writeQueue.submitTracked(
                "bonded_vessel_claim_apply",
                connection -> claimInTransaction(connection, operationId, nowMs),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> applyAsync(
            @Nonnull AppliedTransition transition) {
        return writeQueue.submitTracked(
                "bonded_vessel_apply",
                connection -> applyInTransaction(connection, transition),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> commitAsync(
            @Nonnull String operationId,
            long nowMs) {
        return writeQueue.submitTracked(
                "bonded_vessel_commit",
                connection -> closeAppliedInTransaction(connection, operationId, nowMs),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> cancelPreparedAsync(
            @Nonnull String operationId,
            @Nonnull String reason,
            long nowMs) {
        return writeQueue.submitTracked(
                "bonded_vessel_cancel",
                connection -> cancelPreparedInTransaction(connection, operationId, reason, nowMs),
                null
        );
    }

    /**
     * Closes a prepared or claimed operation only when the caller supplies state-specific proof
     * that Tamework's authoritative apply boundary was never entered.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> denyBeforeApplyAsync(
            @Nonnull String operationId,
            @Nonnull String reason,
            @Nonnull ApplyAbsenceProof proof,
            long nowMs) {
        return writeQueue.submitTracked(
                "bonded_vessel_terminal_deny",
                connection -> denyBeforeApplyInTransaction(
                        connection, operationId, reason, proof, nowMs),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> quarantineAsync(
            @Nonnull String operationId,
            @Nonnull String reason,
            long nowMs) {
        return writeQueue.submitTracked(
                "bonded_vessel_quarantine",
                connection -> quarantineInTransaction(connection, operationId, reason, nowMs),
                null
        );
    }

    @Nullable
    public BondedVesselBindingRecord findBinding(@Nonnull String bindingId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findBinding(connection, bindingId);
        }
    }

    @Nullable
    public BondedVesselBindingRecord findBindingByProfile(@Nonnull String profileId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findBindingByProfile(connection, requireText(profileId, "profileId"));
        }
    }

    /** Bootstrap snapshot for command-link-independent lifecycle qualification. */
    @Nonnull
    public List<BondedVesselBindingRecord> loadNonReleasedBindings() throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return BondedVesselTransitionStore.loadNonReleasedBindings(connection);
        }
    }

    @Nullable
    public BondedVesselOperationRecord findOperation(@Nonnull String operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findOperation(connection, operationId);
        }
    }

    @Nullable
    public BondedVesselOperationRecord findOperationByCallerKey(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findOperationByCallerKey(connection, callerNamespace, idempotencyKey);
        }
    }

    @Nonnull
    public List<BondedVesselOperationRecord> loadRecoverableOperations() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     OPERATION_COLUMNS + " WHERE state IN "
                             + "('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING', 'QUARANTINED') "
                             + "ORDER BY created_at_ms, operation_id");
             ResultSet result = statement.executeQuery()) {
            List<BondedVesselOperationRecord> operations = new ArrayList<>();
            while (result.next()) {
                operations.add(readOperation(result));
            }
            return List.copyOf(operations);
        }
    }

    @Nonnull
    private MutationResult createInitialInTransaction(
            @Nonnull Connection connection,
            @Nonnull BondedVesselBindingRecord binding,
            @Nonnull BondedVesselOperationRecord operation) throws Exception {
        BondedVesselOperationRecord existingOperation = findExistingOperation(connection, operation);
        if (existingOperation != null) {
            BondedVesselBindingRecord existingBinding = findBinding(connection, existingOperation.bindingId());
            return sameTransition(existingOperation, operation)
                    ? result(Status.IDEMPOTENT, existingBinding, existingOperation, "operation_exists")
                    : result(Status.CONFLICT, existingBinding, existingOperation, "idempotency_key_in_use");
        }
        BondedVesselBindingRecord existingBinding = findBinding(connection, binding.bindingId());
        if (existingBinding == null) {
            existingBinding = findBindingByProfile(connection, binding.profileId());
        }
        if (existingBinding != null) {
            return result(Status.CONFLICT, existingBinding, null, "binding_or_profile_in_use");
        }
        insertBinding(connection, binding);
        insertOperation(connection, operation);
        return result(Status.APPLIED, binding, operation, null);
    }

    @Nonnull
    private MutationResult prepareInTransaction(
            @Nonnull Connection connection,
            @Nonnull BondedVesselOperationRecord requested) throws Exception {
        BondedVesselOperationRecord existing = findExistingOperation(connection, requested);
        if (existing != null) {
            return sameTransition(existing, requested)
                    ? result(Status.IDEMPOTENT, findBinding(connection, existing.bindingId()),
                            existing, "operation_exists")
                    : result(Status.CONFLICT, findBinding(connection, existing.bindingId()),
                            existing, "idempotency_key_in_use");
        }
        BondedVesselBindingRecord binding = findBinding(connection, requested.bindingId());
        String denial = validatePreparation(binding, requested);
        if (denial != null) {
            return result(Status.DENIED, binding, null, denial);
        }
        insertOperation(connection, requested);
        reserveOperation(connection, requested);
        return result(Status.PREPARED, findBinding(connection, requested.bindingId()), requested, null);
    }

    @Nonnull
    private MutationResult claimInTransaction(@Nonnull Connection connection,
                                              @Nonnull String operationId,
                                              long nowMs) throws Exception {
        BondedVesselOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        BondedVesselBindingRecord binding = findBinding(connection, operation.bindingId());
        if (operation.state() == BondedVesselOperationRecord.State.APPLYING) {
            return result(Status.IDEMPOTENT, binding, operation, "already_claimed");
        }
        if (operation.state() != BondedVesselOperationRecord.State.PREPARED) {
            return result(Status.INVALID_STATE, binding, operation, "operation_not_prepared");
        }
        if (!bindingMatchesReservation(binding, operation)) {
            return result(Status.CONFLICT, binding, operation, "binding_reservation_changed");
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            updateBindingForClaim(connection, operation, nowMs);
            updateOperationState(connection, operation.operationId(),
                    BondedVesselOperationRecord.State.PREPARED,
                    BondedVesselOperationRecord.State.APPLYING, null, nowMs, 0L);
            connection.releaseSavepoint(savepoint);
        } catch (Exception failure) {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            throw failure;
        }
        return result(Status.APPLYING, findBinding(connection, operation.bindingId()),
                findOperation(connection, operation.operationId()), null);
    }

    @Nonnull
    private MutationResult applyInTransaction(@Nonnull Connection connection,
                                              @Nonnull AppliedTransition transition) throws Exception {
        BondedVesselOperationRecord operation = findOperation(connection, transition.operationId());
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        BondedVesselBindingRecord binding = findBinding(connection, operation.bindingId());
        if (operation.state() == BondedVesselOperationRecord.State.APPLIED
                || operation.state() == BondedVesselOperationRecord.State.COMMITTED) {
            return result(Status.IDEMPOTENT, binding, operation, "already_applied");
        }
        String denial = validateApply(binding, operation, transition);
        if (denial != null) {
            return result(Status.CONFLICT, binding, operation, denial);
        }
        Savepoint savepoint = connection.setSavepoint();
        try {
            updateBindingForApply(connection, operation, transition);
            updateOperationApplied(connection, operation, transition);
            connection.releaseSavepoint(savepoint);
        } catch (Exception failure) {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            throw failure;
        }
        return result(Status.APPLIED, findBinding(connection, operation.bindingId()),
                findOperation(connection, operation.operationId()), null);
    }

    @Nonnull
    private MutationResult closeAppliedInTransaction(@Nonnull Connection connection,
                                                     @Nonnull String operationId,
                                                     long nowMs) throws Exception {
        BondedVesselOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        BondedVesselBindingRecord binding = findBinding(connection, operation.bindingId());
        if (operation.state() == BondedVesselOperationRecord.State.COMMITTED) {
            return result(Status.IDEMPOTENT, binding, operation, "already_committed");
        }
        if (operation.state() != BondedVesselOperationRecord.State.APPLIED
                || binding == null
                || !operation.operationId().equals(binding.activeOperationId())
                || binding.generation() != operation.candidateGeneration()) {
            return result(Status.INVALID_STATE, binding, operation, "operation_not_applied");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_bindings
                SET active_operation_id = NULL, row_revision = row_revision + 1, updated_at_ms = ?
                WHERE binding_id = ? AND generation = ? AND active_operation_id = ?
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, operation.bindingId());
            statement.setLong(3, operation.candidateGeneration());
            statement.setString(4, operation.operationId());
            if (statement.executeUpdate() != 1) {
                return result(Status.CONFLICT, binding, operation, "binding_changed_before_commit");
            }
        }
        updateOperationState(connection, operation.operationId(), BondedVesselOperationRecord.State.APPLIED,
                BondedVesselOperationRecord.State.COMMITTED, null, nowMs, nowMs);
        return result(Status.COMMITTED, findBinding(connection, operation.bindingId()),
                findOperation(connection, operation.operationId()), null);
    }

    @Nonnull
    private MutationResult cancelPreparedInTransaction(@Nonnull Connection connection,
                                                       @Nonnull String operationId,
                                                       @Nonnull String reason,
                                                       long nowMs) throws Exception {
        BondedVesselOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        BondedVesselBindingRecord binding = findBinding(connection, operation.bindingId());
        if (operation.state() == BondedVesselOperationRecord.State.CANCELED) {
            return result(Status.IDEMPOTENT, binding, operation, "already_canceled");
        }
        if (operation.state() != BondedVesselOperationRecord.State.PREPARED) {
            return result(Status.INVALID_STATE, binding, operation, "operation_already_claimed");
        }
        clearActiveOperation(connection, operation, nowMs);
        updateOperationState(connection, operation.operationId(), BondedVesselOperationRecord.State.PREPARED,
                BondedVesselOperationRecord.State.CANCELED, reason, nowMs, nowMs);
        return result(Status.CANCELED, findBinding(connection, operation.bindingId()),
                findOperation(connection, operation.operationId()), null);
    }

    @Nonnull
    private MutationResult denyBeforeApplyInTransaction(
            @Nonnull Connection connection,
            @Nonnull String operationId,
            @Nonnull String reason,
            @Nonnull ApplyAbsenceProof proof,
            long nowMs) throws Exception {
        BondedVesselOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        BondedVesselBindingRecord binding = findBinding(connection, operation.bindingId());
        if (operation.state() == BondedVesselOperationRecord.State.TERMINAL_DENIED) {
            return result(Status.IDEMPOTENT, binding, operation, "already_terminal_denied");
        }
        BondedVesselOperationRecord.State expectedState = switch (proof) {
            case PREPARED_NOT_CLAIMED -> BondedVesselOperationRecord.State.PREPARED;
            case APPLYING_SOURCE_REVALIDATION_FAILED_BEFORE_MUTATION ->
                    BondedVesselOperationRecord.State.APPLYING;
        };
        if (operation.state() != expectedState) {
            return result(Status.INVALID_STATE, binding, operation,
                    "apply_absence_proof_does_not_match_state");
        }
        if (binding == null
                || binding.generation() != operation.priorGeneration()
                || !operation.operationId().equals(binding.activeOperationId())) {
            return result(Status.CONFLICT, binding, operation, "binding_reservation_changed");
        }
        BondedVesselBindingRecord.LifecycleState expectedLifecycle =
                expectedState == BondedVesselOperationRecord.State.PREPARED
                        ? operation.priorLifecycleState() : operation.applyingLifecycleState();
        if (binding.lifecycleState() != expectedLifecycle) {
            return result(Status.CONFLICT, binding, operation, "binding_lifecycle_changed");
        }
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_bindings
                SET lifecycle_state = ?, active_operation_id = NULL,
                    row_revision = row_revision + 1, updated_at_ms = ?
                WHERE binding_id = ? AND generation = ? AND active_operation_id = ?
                  AND lifecycle_state = ?
                """)) {
            statement.setString(1, operation.priorLifecycleState().name());
            statement.setLong(2, nowMs);
            statement.setString(3, operation.bindingId());
            statement.setLong(4, operation.priorGeneration());
            statement.setString(5, operation.operationId());
            statement.setString(6, expectedLifecycle.name());
            if (statement.executeUpdate() != 1) {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                return result(Status.CONFLICT, findBinding(connection, operation.bindingId()),
                        operation, "binding_changed_before_terminal_denial");
            }
            updateOperationState(connection, operation.operationId(), expectedState,
                    BondedVesselOperationRecord.State.TERMINAL_DENIED,
                    requireText(reason, "reason"), nowMs, nowMs);
            connection.releaseSavepoint(savepoint);
        } catch (Exception failure) {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            throw failure;
        }
        return result(Status.TERMINAL_DENIED, findBinding(connection, operation.bindingId()),
                findOperation(connection, operation.operationId()), null);
    }

    @Nonnull
    private MutationResult quarantineInTransaction(@Nonnull Connection connection,
                                                   @Nonnull String operationId,
                                                   @Nonnull String reason,
                                                   long nowMs) throws Exception {
        BondedVesselOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        if (operation.state().isTerminal()) {
            return result(Status.INVALID_STATE, findBinding(connection, operation.bindingId()),
                    operation, "terminal_operation");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_operations
                SET state = 'QUARANTINED', reason_code = ?, recovery_status = 'QUARANTINED',
                    updated_at_ms = ?
                WHERE operation_id = ? AND state NOT IN ('COMMITTED', 'CANCELED', 'TERMINAL_DENIED')
                """)) {
            statement.setString(1, requireText(reason, "reason"));
            statement.setLong(2, nowMs);
            statement.setString(3, operationId);
            if (statement.executeUpdate() != 1) {
                return result(Status.CONFLICT, findBinding(connection, operation.bindingId()),
                        findOperation(connection, operationId), "operation_changed");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_bindings
                SET item_projection_status = 'QUARANTINED', diagnostic_reason = ?,
                    row_revision = row_revision + 1, updated_at_ms = ?
                WHERE binding_id = ? AND active_operation_id = ?
                """)) {
            statement.setString(1, reason);
            statement.setLong(2, nowMs);
            statement.setString(3, operation.bindingId());
            statement.setString(4, operationId);
            statement.executeUpdate();
        }
        return result(Status.QUARANTINED, findBinding(connection, operation.bindingId()),
                findOperation(connection, operationId), null);
    }

    private String validatePreparation(@Nullable BondedVesselBindingRecord binding,
                                       BondedVesselOperationRecord operation) {
        if (binding == null) return "binding_not_found";
        if (!binding.profileId().equals(operation.profileId())) return "profile_mismatch";
        if (binding.generation() != operation.priorGeneration()) return "stale_generation";
        if (binding.expectedProfileRevision() != operation.expectedProfileRevision()) return "profile_revision_changed";
        if (!binding.configId().equals(operation.configId())
                || binding.configRevision() != operation.configRevision()) return "config_revision_changed";
        if (binding.lifecycleState() != operation.priorLifecycleState()) return "lifecycle_changed";
        if (binding.itemProjectionStatus() != operation.priorProjectionStatus()) return "projection_changed";
        if (binding.cooldownUntilMs() != operation.priorCooldownUntilMs()) return "cooldown_changed";
        if (binding.activeOperationId() != null) return "operation_in_flight";
        return null;
    }

    private String validateApply(@Nullable BondedVesselBindingRecord binding,
                                 BondedVesselOperationRecord operation,
                                 AppliedTransition transition) {
        if (operation.state() != BondedVesselOperationRecord.State.APPLYING) return "operation_not_applying";
        if (binding == null || !operation.operationId().equals(binding.activeOperationId())) return "binding_reservation_changed";
        if (binding.generation() != operation.priorGeneration()) return "stale_generation";
        if (binding.lifecycleState() != operation.applyingLifecycleState()) return "lifecycle_changed";
        if (transition.committedProfileRevision() < operation.expectedProfileRevision()) return "profile_revision_regressed";
        return null;
    }

    private boolean bindingMatchesReservation(@Nullable BondedVesselBindingRecord binding,
                                              BondedVesselOperationRecord operation) {
        return binding != null
                && operation.operationId().equals(binding.activeOperationId())
                && binding.generation() == operation.priorGeneration()
                && binding.lifecycleState() == operation.priorLifecycleState();
    }

    private void validateInitial(BondedVesselBindingRecord binding,
                                 BondedVesselOperationRecord operation) {
        if (binding.generation() != 1L || operation.action() != BondedVesselOperationRecord.Action.INITIAL_BIND
                || operation.priorGeneration() != 0L || operation.candidateGeneration() != 1L
                || !binding.bindingId().equals(operation.bindingId())
                || !binding.profileId().equals(operation.profileId())
                || binding.lifecycleState() != operation.targetLifecycleState()
                || (operation.state() != BondedVesselOperationRecord.State.APPLIED
                    && operation.state() != BondedVesselOperationRecord.State.COMMITTED)) {
            throw new IllegalArgumentException("Initial binding and operation evidence do not match.");
        }
        if (operation.state() == BondedVesselOperationRecord.State.APPLIED
                && !operation.operationId().equals(binding.activeOperationId())) {
            throw new IllegalArgumentException("Applied initial binding must retain its source-finalization operation.");
        }
        if (operation.state() == BondedVesselOperationRecord.State.COMMITTED
                && binding.activeOperationId() != null) {
            throw new IllegalArgumentException("Committed initial binding cannot retain an active operation.");
        }
    }

    private boolean sameTransition(BondedVesselOperationRecord left,
                                   BondedVesselOperationRecord right) {
        return left.bindingId().equals(right.bindingId())
                && left.profileId().equals(right.profileId())
                && left.action() == right.action()
                && left.priorGeneration() == right.priorGeneration()
                && left.candidateGeneration() == right.candidateGeneration();
    }

    private BondedVesselBindingRecord findBinding(Connection connection, String bindingId)
            throws Exception {
        return BondedVesselTransitionStore.findBinding(
                connection, requireText(bindingId, "bindingId"));
    }

    private BondedVesselBindingRecord findBindingByProfile(Connection connection, String profileId)
            throws Exception {
        return BondedVesselTransitionStore.findBindingByProfile(
                connection, requireText(profileId, "profileId"));
    }

    private BondedVesselOperationRecord findOperation(Connection connection, String operationId)
            throws Exception {
        return BondedVesselTransitionStore.findOperation(
                connection, requireText(operationId, "operationId"));
    }

    private BondedVesselOperationRecord findOperationByCallerKey(
            Connection connection, String callerNamespace, String idempotencyKey) throws Exception {
        return BondedVesselTransitionStore.findOperationByCallerKey(
                connection, requireText(callerNamespace, "callerNamespace"),
                requireText(idempotencyKey, "idempotencyKey"));
    }

    private BondedVesselOperationRecord findExistingOperation(
            Connection connection, BondedVesselOperationRecord operation) throws Exception {
        return BondedVesselTransitionStore.findExistingOperation(connection, operation);
    }

    private MutationResult result(Status status, @Nullable BondedVesselBindingRecord binding,
                                  @Nullable BondedVesselOperationRecord operation,
                                  @Nullable String reason) {
        return new MutationResult(status, binding, operation, reason);
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
        TERMINAL_DENIED,
        QUARANTINED,
        IDEMPOTENT,
        DENIED,
        NOT_FOUND,
        INVALID_STATE,
        CONFLICT
    }

    /** Evidence class constraining terminal denial to a pre-authoritative-mutation boundary. */
    public enum ApplyAbsenceProof {
        PREPARED_NOT_CLAIMED,
        APPLYING_SOURCE_REVALIDATION_FAILED_BEFORE_MUTATION
    }

    public record MutationResult(@Nonnull Status status,
                                 @Nullable BondedVesselBindingRecord binding,
                                 @Nullable BondedVesselOperationRecord operation,
                                 @Nullable String reason) {
    }

    /** Exact canonical values committed with population/profile apply. */
    public record AppliedTransition(@Nonnull String operationId,
                                    long committedProfileRevision,
                                    @Nullable UUID activeNpcUuid,
                                    @Nullable BondedVesselBindingRecord.PhysicalLocation activeLocation,
                                    @Nullable String itemEvidenceJson,
                                    @Nullable String reasonCode,
                                    long appliedAtMs) {
        public AppliedTransition {
            operationId = requireText(operationId, "operationId");
            if (committedProfileRevision < 0L) {
                throw new IllegalArgumentException("committedProfileRevision must be non-negative.");
            }
        }
    }
}

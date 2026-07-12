package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.ReleaseRequest;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.deployedCaptureMatches;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.deployedResidentMatches;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.differentResident;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.hasReached;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.housedReleaseMatches;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.isCaptureState;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.isReleaseState;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.matchesCapture;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.matchesRelease;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.matchesResident;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.releasingResidentMatches;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.requireText;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.validateCapture;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.validateGeneration;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRules.validateRelease;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.HousedResidentClaim;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;

/** Transactional coordinator for durable managed-coop capture and release operations. */
final class CoopLifecycleOperationTransactions {
    private final ManagedCoopResidentRepository residents;
    private final CoopLifecycleOperationStore store = new CoopLifecycleOperationStore();

    CoopLifecycleOperationTransactions(@Nonnull ManagedCoopResidentRepository residents) {
        this.residents = Objects.requireNonNull(residents, "residents");
    }

    MutationResult prepareCapture(Connection connection, CaptureRequest request) throws SQLException {
        validateCapture(request);
        OperationRecord existing = store.load(connection, request.operationId());
        if (existing != null) {
            if (!matchesCapture(existing, request) || !isCaptureState(existing.state())) {
                return conflict(existing, "capture_operation_identity_conflict");
            }
            if (existing.state() == OperationState.PREPARED) {
                String conflict = capturePrecondition(connection, request);
                return conflict == null ? idempotent(existing) : conflict(existing, conflict);
            }
            return housedCaptureMatches(connection, request)
                    ? idempotent(existing)
                    : conflict(existing, "captured_resident_state_conflict");
        }
        String conflict = capturePrecondition(connection, request);
        if (conflict != null) {
            return conflict(null, conflict);
        }
        store.insertCapture(connection, request);
        return applied(requireOperation(connection, request.operationId()));
    }

    MutationResult claimCapture(Connection connection, CaptureRequest request) throws SQLException {
        ManagedCoopCaptureClaimValidator.validate(request);
        Savepoint captureBoundary = connection.setSavepoint();
        MutationResult prepared = prepareCapture(connection, request);
        if (!prepared.succeeded() || prepared.operation() == null) {
            connection.releaseSavepoint(captureBoundary);
            return prepared;
        }
        MutationResult committed = commitCaptureSlot(
                connection,
                request,
                prepared.operation().generation()
        );
        if (committed.succeeded()) {
            connection.releaseSavepoint(captureBoundary);
            return committed;
        }
        connection.rollback(captureBoundary);
        connection.releaseSavepoint(captureBoundary);
        return new MutationResult(committed.status(), null, committed.detail());
    }

    MutationResult commitCaptureSlot(Connection connection,
                                     CaptureRequest request,
                                     long expectedOperationGeneration) throws SQLException {
        validateGeneration(expectedOperationGeneration);
        OperationRecord operation = store.load(connection, request.operationId());
        if (operation == null) {
            return notFound("capture_operation_not_found");
        }
        if (!matchesCapture(operation, request) || !isCaptureState(operation.state())) {
            return conflict(operation, "capture_operation_identity_conflict");
        }
        if (operation.state() != OperationState.PREPARED) {
            return housedCaptureMatches(connection, request)
                    ? idempotent(operation)
                    : conflict(operation, "captured_resident_state_conflict");
        }
        if (!operation.active() || operation.generation() != expectedOperationGeneration) {
            return conflict(operation, "operation_generation_conflict");
        }
        String conflict = capturePrecondition(connection, request);
        if (conflict != null) {
            return conflict(operation, conflict);
        }
        ManagedCoopResidentRepository.MutationResult residentResult = commitResidentCapture(
                connection,
                request
        );
        if (!residentResult.succeeded()) {
            return conflict(operation, residentResult.detail());
        }
        if (!store.advance(connection, request.operationId(), OperationKind.CAPTURE,
                OperationState.PREPARED, OperationState.SLOT_COMMITTED,
                expectedOperationGeneration, false, request.nowMs())) {
            throw new SQLException("capture_operation_generation_changed_after_resident_commit");
        }
        return applied(requireOperation(connection, request.operationId()));
    }

    MutationResult advance(Connection connection,
                           String operationId,
                           OperationKind kind,
                           OperationState expected,
                           OperationState target,
                           long expectedOperationGeneration,
                           boolean terminal,
                           long nowMs) throws SQLException {
        validateGeneration(expectedOperationGeneration);
        OperationRecord operation = store.load(connection, requireText(operationId, "operationId"));
        if (operation == null) {
            return notFound("lifecycle_operation_not_found");
        }
        if (operation.kind() != kind) {
            return conflict(operation, "operation_kind_conflict");
        }
        if (hasReached(operation.kind(), operation.state(), target)) {
            return idempotent(operation);
        }
        if (!operation.active() || operation.state() != expected
                || operation.generation() != expectedOperationGeneration) {
            return conflict(operation, "operation_state_or_generation_conflict");
        }
        if (!store.advance(connection, operationId, kind, expected, target,
                expectedOperationGeneration, terminal, nowMs)) {
            return conflict(requireOperation(connection, operationId), "operation_cas_conflict");
        }
        return applied(requireOperation(connection, operationId));
    }

    MutationResult prepareRelease(Connection connection, ReleaseRequest request) throws SQLException {
        validateRelease(request);
        OperationRecord existing = store.load(connection, request.operationId());
        if (existing != null) {
            if (!matchesRelease(existing, request) || !isReleaseState(existing.state())) {
                return conflict(existing, "release_operation_identity_conflict");
            }
            return releaseReplayMatches(connection, existing)
                    ? idempotent(existing)
                    : conflict(existing, "release_resident_state_conflict");
        }
        String conflict = releasePrecondition(connection, request);
        if (conflict != null) {
            return conflict(null, conflict);
        }
        ManagedCoopResidentRepository.MutationResult residentResult = residents.beginReleaseInTransaction(
                connection,
                request.residentId(),
                request.expectedResidentGeneration(),
                request.plannedTargetUuid(),
                request.nowMs()
        );
        if (!residentResult.succeeded()) {
            return conflict(null, residentResult.detail());
        }
        store.insertRelease(connection, request);
        return applied(requireOperation(connection, request.operationId()));
    }

    MutationResult markProjectionCreated(Connection connection,
                                         String operationId,
                                         long expectedOperationGeneration,
                                         UUID actualTargetUuid,
                                         long nowMs) throws SQLException {
        validateGeneration(expectedOperationGeneration);
        Objects.requireNonNull(actualTargetUuid, "actualTargetUuid");
        OperationRecord operation = store.load(connection, requireText(operationId, "operationId"));
        if (operation == null) {
            return notFound("release_operation_not_found");
        }
        if (operation.kind() != OperationKind.RELEASE) {
            return conflict(operation, "operation_kind_conflict");
        }
        if (operation.state() == OperationState.PROJECTION_CREATED
                || operation.state() == OperationState.FINALIZED) {
            return actualTargetUuid.equals(operation.actualTargetUuid())
                    ? idempotent(operation)
                    : conflict(operation, "projection_uuid_conflict");
        }
        if (!operation.active() || operation.state() != OperationState.SPAWN_CLAIMED
                || operation.generation() != expectedOperationGeneration) {
            return conflict(operation, "operation_state_or_generation_conflict");
        }
        ResidentRecord resident = residentForOperation(connection, operation);
        String conflict = projectionPrecondition(connection, operation, resident, actualTargetUuid);
        if (conflict != null) {
            return conflict(operation, conflict);
        }
        ManagedCoopResidentRepository.MutationResult reservation =
                residents.reserveProjectionUuidInTransaction(
                        connection, resident.residentId(), actualTargetUuid, nowMs);
        if (!reservation.succeeded()) {
            return conflict(operation, reservation.detail());
        }
        if (!store.markProjectionCreated(
                connection, operationId, expectedOperationGeneration, actualTargetUuid, nowMs)) {
            throw new SQLException("projection_operation_generation_changed_after_uuid_reservation");
        }
        return applied(requireOperation(connection, operationId));
    }

    MutationResult finalizeRelease(Connection connection,
                                   String operationId,
                                   long expectedOperationGeneration,
                                   long nowMs) throws SQLException {
        validateGeneration(expectedOperationGeneration);
        OperationRecord operation = store.load(connection, requireText(operationId, "operationId"));
        if (operation == null) {
            return notFound("release_operation_not_found");
        }
        if (operation.kind() != OperationKind.RELEASE) {
            return conflict(operation, "operation_kind_conflict");
        }
        ResidentRecord resident = residentForOperation(connection, operation);
        if (operation.state() == OperationState.FINALIZED) {
            return deployedResidentMatches(operation, resident)
                    ? idempotent(operation)
                    : conflict(operation, "finalized_resident_state_conflict");
        }
        if (!operation.active() || operation.state() != OperationState.PROJECTION_CREATED
                || operation.generation() != expectedOperationGeneration
                || operation.actualTargetUuid() == null) {
            return conflict(operation, "operation_state_or_generation_conflict");
        }
        if (!releasingResidentMatches(operation, resident)) {
            return conflict(operation, "releasing_resident_state_conflict");
        }
        ManagedCoopResidentRepository.MutationResult residentResult = residents.finishReleaseInTransaction(
                connection,
                resident.residentId(),
                operation.expectedResidentGeneration() + 1L,
                operation.actualTargetUuid(),
                nowMs
        );
        if (!residentResult.succeeded()) {
            return conflict(operation, residentResult.detail());
        }
        if (!store.advance(connection, operationId, OperationKind.RELEASE,
                OperationState.PROJECTION_CREATED, OperationState.FINALIZED,
                expectedOperationGeneration, true, nowMs)) {
            throw new SQLException("release_operation_generation_changed_after_resident_deployment");
        }
        return applied(requireOperation(connection, operationId));
    }

    @Nullable
    OperationRecord load(Connection connection, String operationId) throws SQLException {
        return store.load(connection, requireText(operationId, "operationId"));
    }

    @Nullable
    OperationRecord loadActiveForProfile(Connection connection, String profileId) throws SQLException {
        return store.loadActiveForProfile(connection, requireText(profileId, "profileId"));
    }

    @Nullable
    private String capturePrecondition(Connection connection, CaptureRequest request) throws SQLException {
        String common = commonPrecondition(connection, request.operationId(), request.profileId(),
                request.authorityKey(), request.residentSlot());
        if (common != null) {
            return common;
        }
        if (!store.authorityIsManaged(connection, request.authorityKey(), request.coopId())) {
            return "managed_authority_not_found";
        }
        ResidentRecord byId = residents.loadByIdInTransaction(connection, request.residentId());
        ResidentRecord byProfile = residents.loadActiveByProfileInTransaction(connection, request.profileId());
        ResidentRecord bySlot = residents.loadActiveSlotInTransaction(
                connection, request.authorityKey(), request.residentSlot());
        if (byId == null) {
            if (request.expectedResidentGeneration() != 0L || byProfile != null || bySlot != null) {
                return "empty_slot_capture_assignment_conflict";
            }
        } else if (!deployedCaptureMatches(request, byId)
                || differentResident(byProfile, byId) || differentResident(bySlot, byId)) {
            return "deployed_capture_precondition_conflict";
        }
        if (store.hasUuidClaimConflict(connection, request.residentId(), request.sourceNpcUuid())
                || store.hasResidentUuidConflict(
                        connection, request.residentId(), request.sourceNpcUuid())
                || store.uuidMappedToDifferentProfile(
                        connection, request.sourceNpcUuid(), request.profileId())) {
            return "capture_uuid_conflict";
        }
        if (!sourceMapsToProfile(connection, request.sourceNpcUuid(), request.profileId())) {
            return "capture_source_profile_mapping_conflict";
        }
        if (hasActiveSourceOperationConflict(
                connection, request.operationId(), request.sourceNpcUuid())) {
            return "active_capture_source_operation_conflict";
        }
        return null;
    }

    private boolean sourceMapsToProfile(Connection connection,
                                        UUID sourceNpcUuid,
                                        String profileId) throws SQLException {
        boolean found = false;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                UNION
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                """)) {
            query.setString(1, sourceNpcUuid.toString());
            query.setString(2, sourceNpcUuid.toString());
            try (ResultSet resultSet = query.executeQuery()) {
                while (resultSet.next()) {
                    found = true;
                    if (!profileId.equals(resultSet.getString(1))) {
                        return false;
                    }
                }
            }
        }
        return found;
    }

    private boolean hasActiveSourceOperationConflict(Connection connection,
                                                     String operationId,
                                                     UUID sourceNpcUuid) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM coop_lifecycle_operations
                WHERE active = 1 AND operation_id <> ? AND source_npc_uuid = ?
                LIMIT 1
                """)) {
            query.setString(1, operationId);
            query.setString(2, sourceNpcUuid.toString());
            try (ResultSet resultSet = query.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Nullable
    private String releasePrecondition(Connection connection, ReleaseRequest request) throws SQLException {
        String common = commonPrecondition(connection, request.operationId(), request.profileId(),
                request.authorityKey(), request.residentSlot());
        if (common != null) {
            return common;
        }
        if (!store.authorityAllowsRelease(
                connection, request.authorityKey(), request.coopId())) {
            return "release_authority_not_found";
        }
        ResidentRecord resident = residents.loadByIdInTransaction(connection, request.residentId());
        if (!housedReleaseMatches(request, resident)
                || differentResident(residents.loadActiveByProfileInTransaction(
                        connection, request.profileId()), resident)
                || differentResident(residents.loadActiveSlotInTransaction(
                        connection, request.authorityKey(), request.residentSlot()), resident)) {
            return "housed_release_precondition_conflict";
        }
        if (request.plannedTargetUuid().equals(resident.residentUuid())
                || request.plannedTargetUuid().equals(resident.sourceNpcUuid())
                || store.hasUuidClaimConflict(connection, request.residentId(), request.plannedTargetUuid())
                || store.hasResidentUuidConflict(
                        connection, request.residentId(), request.plannedTargetUuid())
                || store.uuidHasProfileMapping(connection, request.plannedTargetUuid())
                || store.findTargetConflict(connection, request.operationId(), request.plannedTargetUuid()) != null
                || store.hasRecoveryTargetConflict(connection, request.plannedTargetUuid())) {
            return "release_target_uuid_conflict";
        }
        return null;
    }

    @Nullable
    private String commonPrecondition(Connection connection,
                                      String operationId,
                                      String profileId,
                                      ManagedCoopAuthorityKey key,
                                      int residentSlot) throws SQLException {
        if (!store.profileExists(connection, profileId)) {
            return "profile_not_found";
        }
        return store.findActiveConflict(connection, operationId, profileId, key, residentSlot) == null
                ? null
                : "active_profile_or_slot_operation_conflict";
    }

    private ManagedCoopResidentRepository.MutationResult commitResidentCapture(
            Connection connection,
            CaptureRequest request) throws SQLException {
        ResidentRecord resident = residents.loadByIdInTransaction(connection, request.residentId());
        if (resident == null) {
            return residents.claimHousedInTransaction(connection, new HousedResidentClaim(
                    request.residentId(), request.authorityKey(), request.coopId(), request.residentSlot(),
                    request.profileId(), request.roleId(), request.sourceNpcUuid(), request.snapshotJson(),
                    request.snapshotHash(), request.snapshotVersion(), request.nowMs()));
        }
        return residents.finishCaptureInTransaction(connection, request.residentId(),
                request.expectedResidentGeneration(), request.sourceNpcUuid(), request.snapshotJson(),
                request.snapshotHash(), request.snapshotVersion(), request.nowMs());
    }

    @Nullable
    private String projectionPrecondition(Connection connection,
                                          OperationRecord operation,
                                          @Nullable ResidentRecord resident,
                                          UUID actualTargetUuid) throws SQLException {
        if (!releasingResidentMatches(operation, resident)) {
            return "releasing_resident_state_conflict";
        }
        if (store.hasUuidClaimConflict(connection, resident.residentId(), actualTargetUuid)
                || store.hasResidentUuidConflict(
                        connection, resident.residentId(), actualTargetUuid)
                || store.uuidHasProfileMapping(connection, actualTargetUuid)
                || store.findTargetConflict(connection, operation.operationId(), actualTargetUuid) != null
                || store.hasRecoveryTargetConflict(connection, actualTargetUuid)) {
            return "projection_target_uuid_conflict";
        }
        return null;
    }

    private boolean releaseReplayMatches(Connection connection, OperationRecord operation) throws SQLException {
        ResidentRecord resident = residentForOperation(connection, operation);
        if (operation.state() == OperationState.FINALIZED) {
            return deployedResidentMatches(operation, resident);
        }
        return releasingResidentMatches(operation, resident);
    }

    @Nullable
    private ResidentRecord residentForOperation(Connection connection,
                                                OperationRecord operation) throws SQLException {
        ResidentRecord resident = residents.loadActiveSlotInTransaction(
                connection, operation.authorityKey(), operation.residentSlot());
        return resident != null && resident.profileId().equals(operation.profileId()) ? resident : null;
    }

    private boolean housedCaptureMatches(Connection connection, CaptureRequest request) throws SQLException {
        ResidentRecord resident = residents.loadActiveSlotInTransaction(
                connection, request.authorityKey(), request.residentSlot());
        return resident != null && resident.residentId().equals(request.residentId())
                && resident.profileId().equals(request.profileId()) && resident.active()
                && resident.state() == ResidentState.HOUSED
                && request.sourceNpcUuid().equals(resident.sourceNpcUuid())
                && Objects.equals(request.snapshotHash(), resident.snapshotHash())
                && (resident.generation() == request.expectedResidentGeneration()
                    || resident.generation() == request.expectedResidentGeneration() + 1L);
    }

    private OperationRecord requireOperation(Connection connection, String operationId) throws SQLException {
        OperationRecord operation = store.load(connection, operationId);
        if (operation == null) {
            throw new SQLException("lifecycle_operation_missing_after_write:" + operationId);
        }
        return operation;
    }

    private MutationResult applied(OperationRecord operation) {
        return new MutationResult(MutationStatus.APPLIED, operation, null);
    }

    private MutationResult idempotent(OperationRecord operation) {
        return new MutationResult(MutationStatus.IDEMPOTENT, operation, null);
    }

    private MutationResult notFound(String detail) {
        return new MutationResult(MutationStatus.NOT_FOUND, null, detail);
    }

    private MutationResult conflict(@Nullable OperationRecord operation, @Nullable String detail) {
        return new MutationResult(MutationStatus.CONFLICT, operation,
                detail == null || detail.isBlank() ? "lifecycle_conflict" : detail);
    }
}

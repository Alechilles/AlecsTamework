package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.ReleaseRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;

/** Pure validation and identity rules shared by managed-coop lifecycle transactions. */
final class CoopLifecycleOperationRules {
    private CoopLifecycleOperationRules() {
    }

    static void validateCapture(CaptureRequest request) {
        Objects.requireNonNull(request, "request");
        requireText(request.operationId(), "operationId");
        requireText(request.residentId(), "residentId");
        requireText(request.profileId(), "profileId");
        requireText(request.coopId(), "coopId");
        Objects.requireNonNull(request.authorityKey(), "authorityKey");
        Objects.requireNonNull(request.sourceNpcUuid(), "sourceNpcUuid");
        validateSlotAndGeneration(request.residentSlot(), request.expectedResidentGeneration());
    }

    static void validateRelease(ReleaseRequest request) {
        Objects.requireNonNull(request, "request");
        requireText(request.operationId(), "operationId");
        requireText(request.residentId(), "residentId");
        requireText(request.profileId(), "profileId");
        requireText(request.coopId(), "coopId");
        Objects.requireNonNull(request.authorityKey(), "authorityKey");
        Objects.requireNonNull(request.plannedTargetUuid(), "plannedTargetUuid");
        validateSlotAndGeneration(request.residentSlot(), request.expectedResidentGeneration());
    }

    static void validateGeneration(long generation) {
        if (generation < 0L) {
            throw new IllegalArgumentException("operation generation must be non-negative");
        }
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    static boolean matchesCapture(OperationRecord operation, CaptureRequest request) {
        return matchesBase(operation, OperationKind.CAPTURE, request.operationId(), request.profileId(),
                request.authorityKey(), request.coopId(), request.residentSlot(),
                request.expectedResidentGeneration(), request.snapshotHash())
                && Objects.equals(operation.sourceNpcUuid(), request.sourceNpcUuid());
    }

    static boolean matchesRelease(OperationRecord operation, ReleaseRequest request) {
        return matchesBase(operation, OperationKind.RELEASE, request.operationId(), request.profileId(),
                request.authorityKey(), request.coopId(), request.residentSlot(),
                request.expectedResidentGeneration(), request.snapshotHash())
                && Objects.equals(operation.plannedTargetUuid(), request.plannedTargetUuid());
    }

    static boolean matchesResident(ManagedCoopAuthorityKey key,
                                   String coopId,
                                   int slot,
                                   String profileId,
                                   ResidentRecord resident) {
        return resident.active() && resident.authorityKey().equals(key)
                && resident.coopId().equalsIgnoreCase(coopId) && resident.residentSlot() == slot
                && resident.profileId().equals(profileId);
    }

    static boolean deployedCaptureMatches(CaptureRequest request, ResidentRecord resident) {
        return matchesResident(request.authorityKey(), request.coopId(), request.residentSlot(),
                request.profileId(), resident)
                && resident.state() == ResidentState.DEPLOYED
                && resident.generation() == request.expectedResidentGeneration()
                && request.sourceNpcUuid().equals(resident.residentUuid());
    }

    static boolean housedReleaseMatches(ReleaseRequest request, @Nullable ResidentRecord resident) {
        return resident != null && matchesResident(request.authorityKey(), request.coopId(),
                request.residentSlot(), request.profileId(), resident)
                && resident.state() == ResidentState.HOUSED
                && resident.generation() == request.expectedResidentGeneration()
                && Objects.equals(request.snapshotHash(), resident.snapshotHash());
    }

    static boolean releasingResidentMatches(OperationRecord operation,
                                             @Nullable ResidentRecord resident) {
        return resident != null && matchesResident(operation.authorityKey(), operation.coopId(),
                operation.residentSlot(), operation.profileId(), resident)
                && resident.state() == ResidentState.RELEASING
                && resident.generation() == operation.expectedResidentGeneration() + 1L;
    }

    static boolean deployedResidentMatches(OperationRecord operation,
                                            @Nullable ResidentRecord resident) {
        return resident != null && matchesResident(operation.authorityKey(), operation.coopId(),
                operation.residentSlot(), operation.profileId(), resident)
                && resident.state() == ResidentState.DEPLOYED && resident.active()
                && resident.generation() == operation.expectedResidentGeneration() + 2L
                && Objects.equals(operation.actualTargetUuid(), resident.deployedNpcUuid());
    }

    static boolean differentResident(@Nullable ResidentRecord candidate,
                                     @Nullable ResidentRecord expected) {
        return candidate != null && (expected == null
                || !candidate.residentId().equals(expected.residentId()));
    }

    static boolean isCaptureState(OperationState state) {
        return state == OperationState.PREPARED || state == OperationState.SLOT_COMMITTED
                || state == OperationState.SOURCE_RETIRE_REQUESTED || state == OperationState.COMPLETE;
    }

    static boolean isReleaseState(OperationState state) {
        return state == OperationState.PREPARED || state == OperationState.SPAWN_CLAIMED
                || state == OperationState.PROJECTION_CREATED || state == OperationState.FINALIZED;
    }

    static boolean hasReached(OperationKind kind, OperationState current, OperationState target) {
        int currentOrder = stateOrder(kind, current);
        int targetOrder = stateOrder(kind, target);
        return targetOrder >= 0 && currentOrder >= targetOrder;
    }

    private static boolean matchesBase(OperationRecord operation,
                                       OperationKind kind,
                                       String operationId,
                                       String profileId,
                                       ManagedCoopAuthorityKey key,
                                       String coopId,
                                       int slot,
                                       long expectedResidentGeneration,
                                       @Nullable String snapshotHash) {
        return operation.operationId().equals(operationId) && operation.kind() == kind
                && operation.profileId().equals(profileId) && operation.authorityKey().equals(key)
                && operation.coopId().equalsIgnoreCase(coopId) && operation.residentSlot() == slot
                && operation.expectedResidentGeneration() == expectedResidentGeneration
                && Objects.equals(operation.snapshotHash(), snapshotHash);
    }

    private static int stateOrder(OperationKind kind, OperationState state) {
        if (kind == OperationKind.CAPTURE) {
            return switch (state) {
                case PREPARED -> 0;
                case SLOT_COMMITTED -> 1;
                case SOURCE_RETIRE_REQUESTED -> 2;
                case COMPLETE -> 3;
                default -> -1;
            };
        }
        if (kind == OperationKind.RELEASE) {
            return switch (state) {
                case PREPARED -> 0;
                case SPAWN_CLAIMED -> 1;
                case PROJECTION_CREATED -> 2;
                case FINALIZED -> 3;
                default -> -1;
            };
        }
        return -1;
    }

    private static void validateSlotAndGeneration(int residentSlot, long generation) {
        if (residentSlot < 0 || generation < 0L) {
            throw new IllegalArgumentException("resident slot and generation must be non-negative");
        }
    }
}

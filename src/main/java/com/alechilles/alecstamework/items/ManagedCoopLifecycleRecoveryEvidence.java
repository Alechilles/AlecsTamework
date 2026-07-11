package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.Status;
import com.alechilles.alecstamework.items.ManagedCoopLifecycleRecoveryPlanner.ActionKind;
import com.alechilles.alecstamework.items.ManagedCoopLifecycleRecoveryPlanner.RecoveryAction;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseSite;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds one immutable recovery command from a coherent resident/operation index epoch. */
final class ManagedCoopLifecycleRecoveryEvidence {
    enum DecisionStatus {
        READY,
        NONE,
        WAITING,
        BLOCKED,
        RESERVED_IMPORT
    }

    record RecoveryCommand(@Nonnull ActionKind action,
                           @Nonnull OperationRecord operation,
                           @Nullable ResidentRecord resident,
                           @Nullable Status captureSource,
                           @Nullable ReleaseSite releaseSite,
                           long residentRevision,
                           long operationRevision) {
        RecoveryCommand {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(operation, "operation");
        }
    }

    record Decision(@Nonnull DecisionStatus status,
                    @Nullable RecoveryCommand command,
                    @Nullable String detail) {
        Decision {
            Objects.requireNonNull(status, "status");
            if ((status == DecisionStatus.READY) != (command != null)) {
                throw new IllegalArgumentException("recovery evidence command shape mismatch");
            }
        }
    }

    private final ManagedCoopLifecycleRecoveryPlanner planner;
    private final ManagedCoopResidentIndex residents;
    private final ManagedCoopLifecycleOperationIndex operations;
    private final BooleanSupplier compositeTrust;

    ManagedCoopLifecycleRecoveryEvidence(
            @Nonnull ManagedCoopLifecycleRecoveryPlanner planner,
            @Nonnull ManagedCoopResidentIndex residents,
            @Nonnull ManagedCoopLifecycleOperationIndex operations,
            @Nonnull BooleanSupplier compositeTrust) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.compositeTrust = Objects.requireNonNull(compositeTrust, "compositeTrust");
    }

    @Nonnull
    Decision select(@Nonnull String worldName,
                    @Nonnull List<ManagedCoopContext> contexts) {
        SnapshotPair pair = coherentSnapshots();
        if (!pair.available()) {
            return blocked(pair.detail());
        }
        RecoveryAction action;
        try {
            action = planner.plan(worldName, contexts, pair.operations().operations());
        } catch (RuntimeException exception) {
            return blocked(detail("lifecycle_recovery_plan", exception));
        }
        return fromAction(action, pair);
    }

    /** Re-resolves one exact capture after a durable state transition and paired refresh. */
    @Nonnull
    Decision captureById(@Nonnull String operationId) {
        SnapshotPair pair = coherentSnapshots();
        if (!pair.available()) {
            return blocked(pair.detail());
        }
        OperationRecord operation = pair.operations().operationById(operationId);
        if (operation == null || operation.kind() != OperationKind.CAPTURE
                || operation.state() != OperationState.SOURCE_RETIRE_REQUESTED) {
            return blocked("capture_recovery_operation_not_current");
        }
        return capture(ActionKind.RESUME_CAPTURE_SOURCE_RETIREMENT, operation, pair);
    }

    @Nonnull
    private Decision fromAction(RecoveryAction action, SnapshotPair pair) {
        return switch (action.kind()) {
            case NONE -> new Decision(DecisionStatus.NONE, null, null);
            case WAIT_FOR_COOP_CONTEXT ->
                    new Decision(DecisionStatus.WAITING, null, action.detail());
            case BLOCKED_UNSAFE_STATE -> blocked(action.detail());
            case RESERVED_FOR_IMPORT ->
                    new Decision(DecisionStatus.RESERVED_IMPORT, null, action.detail());
            case REQUEST_CAPTURE_SOURCE_RETIREMENT,
                    RESUME_CAPTURE_SOURCE_RETIREMENT ->
                    capture(action.kind(), action.operation(), pair);
            case RESUME_RELEASE -> release(action, pair);
        };
    }

    @Nonnull
    private Decision capture(ActionKind action,
                             @Nullable OperationRecord operation,
                             SnapshotPair pair) {
        if (operation == null) {
            return blocked("capture_recovery_operation_missing");
        }
        ResidentRecord resident = pair.residents().residentByProfile(operation.profileId());
        String invalid = validateCapture(operation, resident);
        if (invalid != null) {
            return blocked(invalid);
        }
        ManagedCoopCaptureSourceEvidence.ReadResult source =
                ManagedCoopCaptureSourceEvidence.read(resident.snapshotJson());
        if (source.status() == Status.INVALID) {
            return blocked(source.detail() != null
                    ? source.detail() : "capture_recovery_source_marker_invalid");
        }
        if (!stillCurrent(pair)) {
            return blocked("managed_coop_index_epoch_changed");
        }
        return ready(new RecoveryCommand(
                action, operation, resident, source.status(), null,
                pair.residents().revision(), pair.operations().revision()));
    }

    @Nonnull
    private Decision release(RecoveryAction action, SnapshotPair pair) {
        if (action.operation() == null || action.context() == null) {
            return blocked("release_recovery_context_or_operation_missing");
        }
        final ReleaseSite site;
        try {
            site = ReleaseSite.copyOf(action.context());
        } catch (RuntimeException exception) {
            return blocked(detail("release_recovery_site", exception));
        }
        if (!stillCurrent(pair)) {
            return blocked("managed_coop_index_epoch_changed");
        }
        return ready(new RecoveryCommand(
                action.kind(), action.operation(), null, null, site,
                pair.residents().revision(), pair.operations().revision()));
    }

    @Nullable
    private String validateCapture(OperationRecord operation,
                                   @Nullable ResidentRecord resident) {
        if (operation.kind() != OperationKind.CAPTURE || !operation.active()
                || operation.sourceNpcUuid() == null || operation.snapshotHash() == null
                || resident == null || !resident.active()
                || resident.state() != ResidentState.HOUSED
                || !resident.profileId().equals(operation.profileId())
                || !resident.authorityKey().equals(operation.authorityKey())
                || !resident.coopId().equalsIgnoreCase(operation.coopId())
                || resident.residentSlot() != operation.residentSlot()
                || !resident.residentUuid().equals(operation.sourceNpcUuid())
                || !Objects.equals(resident.sourceNpcUuid(), operation.sourceNpcUuid())
                || resident.deployedNpcUuid() != null
                || !Objects.equals(resident.snapshotHash(), operation.snapshotHash())
                || !residentGenerationMatches(resident.generation(),
                    operation.expectedResidentGeneration())) {
            return "capture_recovery_resident_operation_mismatch";
        }
        try {
            CaptureRequest request = new CaptureRequest(
                    operation.operationId(), resident.residentId(), operation.authorityKey(),
                    operation.coopId(), operation.residentSlot(), operation.profileId(),
                    resident.roleId(), operation.sourceNpcUuid(), resident.snapshotJson(),
                    resident.snapshotHash(), resident.snapshotVersion(),
                    operation.expectedResidentGeneration(), resident.capturedAtMs());
            ManagedCoopCaptureClaimValidator.validate(request);
            return null;
        } catch (RuntimeException exception) {
            return detail("capture_recovery_identity", exception);
        }
    }

    private boolean residentGenerationMatches(long residentGeneration, long expectedGeneration) {
        return residentGeneration == expectedGeneration
                || expectedGeneration != Long.MAX_VALUE
                && residentGeneration == expectedGeneration + 1L;
    }

    @Nonnull
    private SnapshotPair coherentSnapshots() {
        if (!trusted()) {
            return SnapshotPair.unavailable("managed_coop_indexes_untrusted");
        }
        ManagedCoopResidentIndex.Snapshot residentSnapshot = residents.snapshot();
        ManagedCoopLifecycleOperationIndex.Snapshot operationSnapshot = operations.snapshot();
        SnapshotPair pair = SnapshotPair.available(residentSnapshot, operationSnapshot);
        return stillCurrent(pair)
                ? pair : SnapshotPair.unavailable("managed_coop_index_epoch_changed");
    }

    private boolean stillCurrent(SnapshotPair pair) {
        return pair.available() && trusted()
                && pair.operations().trusted()
                && residents.snapshot().revision() == pair.residents().revision()
                && operations.snapshot().revision() == pair.operations().revision();
    }

    private boolean trusted() {
        try {
            return compositeTrust.getAsBoolean()
                    && residents.isTrusted() && operations.isTrusted();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Decision ready(RecoveryCommand command) {
        return new Decision(DecisionStatus.READY, command, null);
    }

    private Decision blocked(@Nullable String detail) {
        return new Decision(DecisionStatus.BLOCKED, null,
                detail == null || detail.isBlank()
                        ? "managed_coop_recovery_evidence_rejected" : detail);
    }

    private String detail(String stage, RuntimeException exception) {
        String message = exception.getMessage();
        return stage + (message == null || message.isBlank()
                ? ":" + exception.getClass().getSimpleName() : ":" + message);
    }

    private record SnapshotPair(
            @Nullable ManagedCoopResidentIndex.Snapshot residents,
            @Nullable ManagedCoopLifecycleOperationIndex.Snapshot operations,
            @Nullable String detail) {
        static SnapshotPair available(ManagedCoopResidentIndex.Snapshot residents,
                                      ManagedCoopLifecycleOperationIndex.Snapshot operations) {
            return new SnapshotPair(residents, operations, null);
        }

        static SnapshotPair unavailable(String detail) {
            return new SnapshotPair(null, null, detail);
        }

        boolean available() {
            return residents != null && operations != null && detail == null;
        }
    }
}

package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.EvidenceDecision;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RemovalObservation;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.RetirementCommand;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceRetirementService.StateEvidenceGateway;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves exact capture-retirement identity from one coherent resident/operation index epoch.
 */
final class ManagedCoopCaptureRetirementIndexEvidence implements StateEvidenceGateway {
    private final BooleanSupplier compositeTrust;
    private final ManagedCoopResidentIndex residents;
    private final ManagedCoopLifecycleOperationIndex operations;

    ManagedCoopCaptureRetirementIndexEvidence(
            @Nonnull BooleanSupplier compositeTrust,
            @Nonnull ManagedCoopResidentIndex residents,
            @Nonnull ManagedCoopLifecycleOperationIndex operations) {
        this.compositeTrust = Objects.requireNonNull(compositeTrust, "compositeTrust");
        this.residents = Objects.requireNonNull(residents, "residents");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public EvidenceDecision resolve(@Nullable RetirementReady ready) {
        if (!validReadyShape(ready)) {
            return EvidenceDecision.rejected("retirement_ready_invalid");
        }
        SnapshotPair pair = coherentSnapshots();
        if (!pair.available()) {
            return EvidenceDecision.rejected(pair.failure());
        }
        ResidentRecord resident = pair.residents().residentByProfile(ready.profileId());
        String sourceFailure = entitySourceFailure(resident);
        if (sourceFailure != null) {
            return EvidenceDecision.rejected(sourceFailure);
        }
        if (ready.durableState() == OperationState.COMPLETE) {
            return completedReplayMatches(ready, pair)
                    ? EvidenceDecision.alreadyComplete()
                    : EvidenceDecision.rejected("completed_retirement_replay_mismatch");
        }
        if (ready.durableState() != OperationState.SOURCE_RETIRE_REQUESTED) {
            return EvidenceDecision.rejected("source_retirement_state_not_committed");
        }
        OperationRecord operation = pair.operations().operationById(ready.operationId());
        RetirementCommand command = command(operation, resident);
        if (command == null || !readyMatches(ready, command)
                || !activeEvidenceMatches(pair, command, operation, resident)
                || !stillCurrent(pair)) {
            return EvidenceDecision.rejected("source_retirement_ready_identity_mismatch");
        }
        return EvidenceDecision.active(command);
    }

    @Override
    public EvidenceDecision resolve(@Nullable RemovalObservation observation) {
        if (!validRemovalShape(observation)) {
            return EvidenceDecision.rejected("capture_source_removal_marker_invalid");
        }
        SnapshotPair pair = coherentSnapshots();
        if (!pair.available()) {
            return EvidenceDecision.rejected(pair.failure());
        }
        OperationRecord operation = pair.operations().operationById(observation.operationId());
        ResidentRecord resident = operation != null
                ? pair.residents().residentByProfile(operation.profileId()) : null;
        String sourceFailure = entitySourceFailure(resident);
        if (sourceFailure != null) {
            return EvidenceDecision.rejected(sourceFailure);
        }
        RetirementCommand command = command(operation, resident);
        if (command == null || !removalMatches(observation, command)
                || !activeEvidenceMatches(pair, command, operation, resident)
                || !stillCurrent(pair)) {
            return EvidenceDecision.rejected("capture_source_removal_identity_mismatch");
        }
        return EvidenceDecision.active(command);
    }

    @Override
    public EvidenceDecision revalidate(@Nonnull RetirementCommand command) {
        if (command == null) {
            return EvidenceDecision.rejected("retirement_command_required");
        }
        SnapshotPair pair = coherentSnapshots();
        if (!pair.available()) {
            return EvidenceDecision.rejected(pair.failure());
        }
        OperationRecord operation = pair.operations().operationById(command.operationId());
        ResidentRecord resident = pair.residents().residentByProfile(command.profileId());
        String sourceFailure = entitySourceFailure(resident);
        if (sourceFailure != null) {
            return EvidenceDecision.rejected(sourceFailure);
        }
        RetirementCommand current = command(operation, resident);
        if (!command.equals(current)
                || !activeEvidenceMatches(pair, command, operation, resident)
                || !stillCurrent(pair)) {
            return EvidenceDecision.rejected("retirement_index_evidence_changed");
        }
        return EvidenceDecision.active(command);
    }

    @Nullable
    private RetirementCommand command(@Nullable OperationRecord operation,
                                      @Nullable ResidentRecord resident) {
        if (operation == null || resident == null) {
            return null;
        }
        try {
            return new RetirementCommand(
                    Objects.requireNonNull(operation.sourceNpcUuid()),
                    operation.profileId(),
                    resident.residentId(),
                    operation.operationId(),
                    operation.authorityKey(),
                    operation.coopId(),
                    operation.residentSlot(),
                    Objects.requireNonNull(operation.snapshotHash()),
                    operation.expectedResidentGeneration(),
                    operation.generation()
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean activeEvidenceMatches(SnapshotPair pair,
                                          RetirementCommand command,
                                          @Nullable OperationRecord operation,
                                          @Nullable ResidentRecord resident) {
        return exactActiveOperation(command, operation)
                && exactHousedResident(command, resident)
                && entitySourceFailure(resident) == null
                && sameOperation(pair.operations().operationByProfile(command.profileId()), command)
                && sameOperation(pair.operations().operationByUuid(command.sourceNpcUuid()), command)
                && sameOperation(pair.operations().operationAt(
                    command.authorityKey(), command.residentSlot()), command)
                && sameResident(pair.residents().residentByUuid(command.sourceNpcUuid()), command)
                && sameResident(pair.residents().residentAt(
                    command.authorityKey(), command.residentSlot()), command);
    }

    private boolean exactActiveOperation(RetirementCommand command,
                                         @Nullable OperationRecord operation) {
        return operation != null
                && operation.active()
                && operation.kind() == OperationKind.CAPTURE
                && operation.state() == OperationState.SOURCE_RETIRE_REQUESTED
                && operation.operationId().equals(command.operationId())
                && operation.profileId().equals(command.profileId())
                && operation.authorityKey().equals(command.authorityKey())
                && operation.coopId().equals(command.coopId())
                && operation.residentSlot() == command.residentSlot()
                && Objects.equals(operation.sourceNpcUuid(), command.sourceNpcUuid())
                && Objects.equals(operation.snapshotHash(), command.snapshotHash())
                && operation.expectedResidentGeneration()
                    == command.expectedResidentGeneration()
                && operation.generation() == command.operationGeneration();
    }

    private boolean exactHousedResident(RetirementCommand command,
                                        @Nullable ResidentRecord resident) {
        return resident != null
                && resident.active()
                && resident.state() == ResidentState.HOUSED
                && resident.residentId().equals(command.residentId())
                && resident.profileId().equals(command.profileId())
                && resident.authorityKey().equals(command.authorityKey())
                && resident.coopId().equals(command.coopId())
                && resident.residentSlot() == command.residentSlot()
                && resident.residentUuid().equals(command.sourceNpcUuid())
                && Objects.equals(resident.sourceNpcUuid(), command.sourceNpcUuid())
                && resident.deployedNpcUuid() == null
                && Objects.equals(resident.snapshotHash(), command.snapshotHash())
                && housedGenerationMatches(resident.generation(),
                    command.expectedResidentGeneration());
    }

    private boolean completedReplayMatches(RetirementReady ready, SnapshotPair pair) {
        ResidentRecord resident = pair.residents().residentByProfile(ready.profileId());
        boolean residentMatches = resident != null && resident.active()
                && entitySourceFailure(resident) == null
                && resident.state() == ResidentState.HOUSED
                && resident.residentId().equals(ready.residentId())
                && resident.authorityKey().equals(ready.authorityKey())
                && resident.coopId().equals(ready.coopId())
                && resident.residentSlot() == ready.residentSlot()
                && resident.residentUuid().equals(ready.sourceNpcUuid())
                && Objects.equals(resident.sourceNpcUuid(), ready.sourceNpcUuid())
                && Objects.equals(resident.snapshotHash(), ready.snapshotHash());
        return residentMatches
                && ready.operationGeneration() == 3L
                && pair.operations().operationById(ready.operationId()) == null
                && stillCurrent(pair);
    }

    private boolean validReadyShape(@Nullable RetirementReady ready) {
        return ready != null
                && ready.sourceNpcUuid() != null
                && ready.profileId() != null && !ready.profileId().isBlank()
                && ready.residentId() != null && !ready.residentId().isBlank()
                && ready.operationId() != null && !ready.operationId().isBlank()
                && ready.authorityKey() != null
                && ready.coopId() != null && !ready.coopId().isBlank()
                && ready.residentSlot() >= 0
                && ready.snapshotHash() != null && !ready.snapshotHash().isBlank()
                && ready.operationGeneration() >= 0L
                && ready.durableState() != null
                && ready.indexRevision() > 0L;
    }

    private boolean readyMatches(RetirementReady ready, RetirementCommand command) {
        return ready.sourceNpcUuid().equals(command.sourceNpcUuid())
                && ready.profileId().equals(command.profileId())
                && ready.residentId().equals(command.residentId())
                && ready.operationId().equals(command.operationId())
                && ready.authorityKey().equals(command.authorityKey())
                && ready.coopId().equals(command.coopId())
                && ready.residentSlot() == command.residentSlot()
                && ready.snapshotHash().equals(command.snapshotHash())
                && ready.operationGeneration() == command.operationGeneration();
    }

    private boolean removalMatches(RemovalObservation observation,
                                   RetirementCommand command) {
        return observation.removedNpcUuid().equals(command.sourceNpcUuid())
                && Objects.equals(observation.markerSourceNpcUuid(), command.sourceNpcUuid())
                && Objects.equals(observation.profileId(), command.profileId())
                && Objects.equals(observation.operationId(), command.operationId())
                && Objects.equals(observation.authoritySlotKey(), command.authoritySlotKey())
                && observation.operationGeneration() == command.operationGeneration();
    }

    private boolean validRemovalShape(@Nullable RemovalObservation observation) {
        return observation != null
                && TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE.equals(
                    observation.projectionKind())
                && observation.profileId() != null && !observation.profileId().isBlank()
                && observation.operationId() != null && !observation.operationId().isBlank()
                && observation.authoritySlotKey() != null
                && !observation.authoritySlotKey().isBlank()
                && observation.markerSourceNpcUuid() != null
                && observation.removedNpcUuid().equals(observation.markerSourceNpcUuid())
                && observation.operationGeneration() >= 0L;
    }

    private boolean sameOperation(@Nullable OperationRecord operation,
                                  RetirementCommand command) {
        return operation != null && operation.operationId().equals(command.operationId());
    }

    private boolean sameResident(@Nullable ResidentRecord resident,
                                 RetirementCommand command) {
        return resident != null && resident.residentId().equals(command.residentId());
    }

    private boolean housedGenerationMatches(long residentGeneration, long expectedGeneration) {
        if (residentGeneration == expectedGeneration) {
            return true;
        }
        return expectedGeneration != Long.MAX_VALUE
                && residentGeneration == expectedGeneration + 1L;
    }

    @Nullable
    private String entitySourceFailure(@Nullable ResidentRecord resident) {
        if (resident == null || resident.snapshotJson() == null || resident.snapshotHash() == null) {
            return "capture_source_snapshot_evidence_missing";
        }
        try {
            if (!resident.snapshotHash().equals(
                    ManagedCoopCaptureClaimValidator.snapshotSha256(resident.snapshotJson()))) {
                return "capture_source_snapshot_hash_mismatch";
            }
        } catch (RuntimeException exception) {
            return "capture_source_snapshot_hash_invalid";
        }
        ManagedCoopCaptureSourceEvidence.ReadResult source =
                ManagedCoopCaptureSourceEvidence.read(resident.snapshotJson());
        return switch (source.status()) {
            case ENTITY_SOURCE -> null;
            case CAPTURED_ITEM -> "capture_source_requires_item_retirement_receipt";
            case INVALID -> "capture_source_snapshot_marker_invalid";
        };
    }

    private SnapshotPair coherentSnapshots() {
        if (!compositeTrust.getAsBoolean()
                || !residents.isTrusted() || !operations.isTrusted()) {
            return SnapshotPair.unavailable("managed_coop_indexes_untrusted");
        }
        ManagedCoopResidentIndex.Snapshot residentSnapshot = residents.snapshot();
        ManagedCoopLifecycleOperationIndex.Snapshot operationSnapshot = operations.snapshot();
        if (!operationSnapshot.trusted()
                || !compositeTrust.getAsBoolean()
                || residentSnapshot.revision() != residents.snapshot().revision()
                || operationSnapshot.revision() != operations.snapshot().revision()) {
            return SnapshotPair.unavailable("managed_coop_index_epoch_changed");
        }
        return SnapshotPair.available(residentSnapshot, operationSnapshot);
    }

    private boolean stillCurrent(SnapshotPair pair) {
        return pair.available()
                && compositeTrust.getAsBoolean()
                && residents.isTrusted()
                && operations.isTrusted()
                && pair.residents().revision() == residents.snapshot().revision()
                && pair.operations().revision() == operations.snapshot().revision();
    }

    private record SnapshotPair(@Nullable ManagedCoopResidentIndex.Snapshot residents,
                                @Nullable ManagedCoopLifecycleOperationIndex.Snapshot operations,
                                @Nullable String failure) {
        static SnapshotPair available(ManagedCoopResidentIndex.Snapshot residents,
                                      ManagedCoopLifecycleOperationIndex.Snapshot operations) {
            return new SnapshotPair(residents, operations, null);
        }

        static SnapshotPair unavailable(String failure) {
            return new SnapshotPair(null, null, failure);
        }

        boolean available() {
            return residents != null && operations != null && failure == null;
        }
    }
}

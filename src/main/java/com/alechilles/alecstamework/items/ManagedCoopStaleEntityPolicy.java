package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure, fail-closed authority policy for NPCs mapped to managed-coop resident lifecycles.
 *
 * <p>The policy reads only immutable runtime index snapshots. A live entity is allowed solely when
 * it is the exact durable release target and its immutable projection marker proves the same
 * profile, operation, source, authority slot, and release-claim generation. Destructive decisions
 * additionally require a current exact physical-authority/config match from the chunk scanner.
 * Incomplete, conflicting, disabled, removed, or untrusted evidence is deferred.</p>
 */
public final class ManagedCoopStaleEntityPolicy {
    private final ManagedCoopResidentIndex residentIndex;
    private final ManagedCoopLifecycleOperationIndex operationIndex;
    private final ManagedCoopAuthorityEligibilityIndex authorityEligibility;
    private final BooleanSupplier compositeTrust;

    public ManagedCoopStaleEntityPolicy(
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull ManagedCoopAuthorityEligibilityIndex authorityEligibility,
            @Nonnull BooleanSupplier compositeTrust) {
        this.residentIndex = Objects.requireNonNull(residentIndex, "residentIndex");
        this.operationIndex = Objects.requireNonNull(operationIndex, "operationIndex");
        this.authorityEligibility = Objects.requireNonNull(
                authorityEligibility, "authorityEligibility");
        this.compositeTrust = Objects.requireNonNull(compositeTrust, "compositeTrust");
    }

    /** Evaluates one immutable live-NPC observation without persistence or ECS access. */
    @Nonnull
    public Decision decide(@Nonnull Observation observation) {
        Objects.requireNonNull(observation, "observation");
        ManagedCoopResidentIndex.Snapshot residents = residentIndex.snapshot();
        ManagedCoopLifecycleOperationIndex.Snapshot operations = operationIndex.snapshot();
        Evidence evidence = collectEvidence(observation, residents, operations);
        if (!evidence.mapped()) {
            return decision(Action.IGNORE, Reason.UNRELATED_NPC, null, null);
        }
        if (!compositeTrusted(operations)) {
            return defer(Reason.UNTRUSTED_COMPOSITE, evidence);
        }
        if (evidence.conflicting()) {
            return defer(Reason.CONFLICTING_EVIDENCE, evidence);
        }
        if (!authorityCurrentlyManaged(evidence)) {
            return defer(Reason.AUTHORITY_NOT_CURRENTLY_MANAGED, evidence);
        }
        if (evidence.operation() != null) {
            return decideActiveOperation(observation, evidence);
        }
        if (evidence.resident() != null) {
            return decideResident(observation, evidence.resident());
        }
        return defer(Reason.ORPHAN_MANAGED_MARKER, evidence);
    }

    @Nonnull
    private Decision decideActiveOperation(Observation observation, Evidence evidence) {
        OperationRecord operation = evidence.operation();
        if (operation.kind() == OperationKind.CAPTURE) {
            Action action = Objects.equals(observation.npcUuid(), operation.sourceNpcUuid())
                    ? Action.SUPPRESS : Action.DEFER;
            Reason reason = action == Action.SUPPRESS
                    ? Reason.ACTIVE_CAPTURE_SOURCE : Reason.INVALID_CAPTURE_PROJECTION;
            return operationDecision(action, reason, operation);
        }
        if (operation.kind() != OperationKind.RELEASE) {
            return defer(Reason.UNSUPPORTED_OPERATION, evidence);
        }
        return decideActiveRelease(observation, evidence.resident(), operation);
    }

    @Nonnull
    private Decision decideActiveRelease(Observation observation,
                                         @Nullable ResidentRecord resident,
                                         OperationRecord operation) {
        if (!isSpawnVisibleReleaseState(operation.state())) {
            return operationDecision(Action.DEFER, Reason.RELEASE_NOT_SPAWN_VISIBLE, operation);
        }
        UUID plannedTarget = operation.plannedTargetUuid();
        if (plannedTarget == null || operation.actualTargetUuid() != null
                && !plannedTarget.equals(operation.actualTargetUuid())) {
            return operationDecision(Action.DEFER, Reason.RELEASE_TARGET_MISMATCH, operation);
        }
        if (!matchesReleasingResident(resident, operation)) {
            return operationDecision(Action.DEFER, Reason.RELEASE_RESIDENT_MISMATCH, operation);
        }
        if (!observation.npcUuid().equals(plannedTarget)) {
            return historicalAlias(observation.npcUuid(), resident, plannedTarget)
                    ? guardedHistoricalSuppression(
                            resident, plannedTarget, operation.operationId())
                    : operationDecision(Action.DEFER, Reason.RELEASE_TARGET_MISMATCH, operation);
        }
        long markerGeneration = expectedActiveReleaseMarkerGeneration(operation);
        if (!ManagedCoopProjectionMarkerPolicy.matchesRelease(
                observation.marker(), operation.operationId(), operation.profileId(),
                operation.authorityKey().slotKey(operation.residentSlot()),
                resident.sourceNpcUuid(), markerGeneration)) {
            return operationDecision(Action.DEFER, Reason.INVALID_RELEASE_MARKER, operation);
        }
        return decision(
                Action.ALLOW,
                Reason.ACTIVE_RELEASE_PROJECTION,
                operation.profileId(),
                operation.operationId(),
                null,
                historicalSource(resident, plannedTarget));
    }

    @Nonnull
    private Decision decideResident(Observation observation, ResidentRecord resident) {
        if (resident.state() == ResidentState.HOUSED) {
            return housedAlias(observation.npcUuid(), resident)
                    ? residentDecision(Action.SUPPRESS, Reason.HOUSED_ALIAS, resident)
                    : residentDecision(Action.DEFER, Reason.RESIDENT_IDENTITY_MISMATCH, resident);
        }
        if (resident.state() == ResidentState.RELEASING) {
            return residentDecision(Action.DEFER, Reason.RELEASE_OPERATION_MISSING, resident);
        }
        if (resident.state() != ResidentState.DEPLOYED) {
            return residentDecision(Action.DEFER, Reason.BLOCKED_RESIDENT_STATE, resident);
        }
        UUID deployedUuid = resident.deployedNpcUuid();
        if (deployedUuid == null || !observation.npcUuid().equals(deployedUuid)
                || !observation.npcUuid().equals(resident.residentUuid())) {
            return historicalAlias(observation.npcUuid(), resident, deployedUuid)
                    ? guardedHistoricalSuppression(resident, deployedUuid, null)
                    : residentDecision(Action.DEFER, Reason.DEPLOYED_IDENTITY_MISMATCH, resident);
        }
        if (ManagedCoopProjectionMarkerPolicy.matchesFinalizedImport(
                observation.marker(), resident)) {
            return decision(
                    Action.ALLOW,
                    Reason.DEPLOYED_IMPORT_ADOPTION,
                    resident.profileId(),
                    observation.marker().operationId(),
                    null,
                    historicalSource(resident, deployedUuid));
        }
        if (!ManagedCoopProjectionMarkerPolicy.matchesFinalizedRelease(
                observation.marker(), resident)) {
            return residentDecision(Action.DEFER, Reason.INVALID_DEPLOYED_MARKER, resident);
        }
        return decision(
                Action.ALLOW,
                Reason.DEPLOYED_RELEASE_PROJECTION,
                resident.profileId(),
                null,
                null,
                historicalSource(resident, deployedUuid));
    }

    private boolean housedAlias(UUID npcUuid, ResidentRecord resident) {
        return npcUuid.equals(resident.residentUuid())
                || npcUuid.equals(resident.sourceNpcUuid());
    }

    private boolean historicalAlias(UUID npcUuid,
                                    @Nullable ResidentRecord resident,
                                    @Nullable UUID currentUuid) {
        return resident != null && currentUuid != null && resident.sourceNpcUuid() != null
                && npcUuid.equals(resident.sourceNpcUuid())
                && !npcUuid.equals(currentUuid);
    }

    @Nonnull
    private Decision guardedHistoricalSuppression(ResidentRecord resident,
                                                  UUID retainedUuid,
                                                  @Nullable String operationId) {
        return decision(
                Action.SUPPRESS,
                Reason.HISTORICAL_RESIDENT_ALIAS,
                resident.profileId(),
                operationId,
                retainedUuid,
                null);
    }

    @Nullable
    private UUID historicalSource(@Nullable ResidentRecord resident, UUID retainedUuid) {
        UUID source = resident != null ? resident.sourceNpcUuid() : null;
        return source != null && !source.equals(retainedUuid) ? source : null;
    }

    private boolean matchesReleasingResident(@Nullable ResidentRecord resident,
                                             OperationRecord operation) {
        return resident != null && resident.active()
                && resident.state() == ResidentState.RELEASING
                && resident.profileId().equals(operation.profileId())
                && resident.authorityKey().equals(operation.authorityKey())
                && resident.coopId().equalsIgnoreCase(operation.coopId())
                && resident.residentSlot() == operation.residentSlot()
                && resident.generation() == operation.expectedResidentGeneration() + 1L
                && resident.sourceNpcUuid() != null
                && resident.residentUuid().equals(resident.sourceNpcUuid())
                && !operation.plannedTargetUuid().equals(resident.sourceNpcUuid());
    }

    private long expectedActiveReleaseMarkerGeneration(OperationRecord operation) {
        return operation.state() == OperationState.SPAWN_CLAIMED
                ? operation.generation()
                : operation.generation() - 1L;
    }

    private boolean isSpawnVisibleReleaseState(OperationState state) {
        return state == OperationState.SPAWN_CLAIMED
                || state == OperationState.PROJECTION_CREATED;
    }

    private boolean compositeTrusted(ManagedCoopLifecycleOperationIndex.Snapshot operations) {
        boolean suppliedTrust;
        try {
            suppliedTrust = compositeTrust.getAsBoolean();
        } catch (RuntimeException exception) {
            suppliedTrust = false;
        }
        return suppliedTrust && residentIndex.isTrusted() && operations.trusted();
    }

    private boolean authorityCurrentlyManaged(Evidence evidence) {
        ManagedCoopAuthorityEligibilityIndex.Snapshot eligible = authorityEligibility.snapshot();
        OperationRecord operation = evidence.operation();
        if (operation != null) {
            return eligible.contains(operation.authorityKey(), operation.coopId());
        }
        ResidentRecord resident = evidence.resident();
        return resident == null
                || eligible.contains(resident.authorityKey(), resident.coopId());
    }

    @Nonnull
    private Evidence collectEvidence(Observation observation,
                                     ManagedCoopResidentIndex.Snapshot residents,
                                     ManagedCoopLifecycleOperationIndex.Snapshot operations) {
        ArrayList<ResidentRecord> residentCandidates = new ArrayList<>(3);
        ArrayList<OperationRecord> operationCandidates = new ArrayList<>(3);
        add(residentCandidates, residents.residentByUuid(observation.npcUuid()));
        add(operationCandidates, operations.operationByUuid(observation.npcUuid()));
        MarkerEvidence marker = observation.marker();
        if (marker != null) {
            if (marker.profileId() != null) {
                add(residentCandidates, residents.residentByProfile(marker.profileId()));
                add(operationCandidates, operations.operationByProfile(marker.profileId()));
            }
            if (marker.operationId() != null) {
                add(operationCandidates, operations.operationById(marker.operationId()));
            }
        }
        addCrossEvidence(residentCandidates, operationCandidates, residents, operations);
        ResidentRecord resident = first(residentCandidates);
        OperationRecord operation = first(operationCandidates);
        boolean conflicting = conflictingResidents(residentCandidates)
                || conflictingOperations(operationCandidates)
                || !baseIdentityMatches(resident, operation);
        boolean managedMarker = marker != null && marker.indicatesManagedCoop();
        return new Evidence(
                resident,
                operation,
                !residentCandidates.isEmpty() || !operationCandidates.isEmpty() || managedMarker,
                conflicting
        );
    }

    private void addCrossEvidence(List<ResidentRecord> residents,
                                  List<OperationRecord> operations,
                                  ManagedCoopResidentIndex.Snapshot residentSnapshot,
                                  ManagedCoopLifecycleOperationIndex.Snapshot operationSnapshot) {
        OperationRecord operation = first(operations);
        if (operation != null) {
            add(residents, residentSnapshot.residentByProfile(operation.profileId()));
        }
        ResidentRecord resident = first(residents);
        if (resident != null) {
            add(operations, operationSnapshot.operationByProfile(resident.profileId()));
        }
    }

    private boolean baseIdentityMatches(@Nullable ResidentRecord resident,
                                        @Nullable OperationRecord operation) {
        return resident == null || operation == null
                || resident.profileId().equals(operation.profileId())
                && resident.authorityKey().equals(operation.authorityKey())
                && resident.coopId().equalsIgnoreCase(operation.coopId())
                && resident.residentSlot() == operation.residentSlot();
    }

    private boolean conflictingResidents(List<ResidentRecord> candidates) {
        ResidentRecord first = first(candidates);
        for (ResidentRecord candidate : candidates) {
            if (!first.residentId().equals(candidate.residentId())) {
                return true;
            }
        }
        return false;
    }

    private boolean conflictingOperations(List<OperationRecord> candidates) {
        OperationRecord first = first(candidates);
        for (OperationRecord candidate : candidates) {
            if (!first.operationId().equals(candidate.operationId())) {
                return true;
            }
        }
        return false;
    }

    private static <T> void add(List<T> target, @Nullable T value) {
        if (value != null && !target.contains(value)) {
            target.add(value);
        }
    }

    @Nullable
    private static <T> T first(List<T> values) {
        return values.isEmpty() ? null : values.getFirst();
    }

    @Nonnull
    private Decision defer(Reason reason, Evidence evidence) {
        String profileId = evidence.operation() != null
                ? evidence.operation().profileId()
                : evidence.resident() != null ? evidence.resident().profileId() : null;
        String operationId = evidence.operation() != null
                ? evidence.operation().operationId()
                : null;
        return decision(Action.DEFER, reason, profileId, operationId);
    }

    @Nonnull
    private Decision operationDecision(Action action, Reason reason, OperationRecord operation) {
        return decision(action, reason, operation.profileId(), operation.operationId());
    }

    @Nonnull
    private Decision residentDecision(Action action, Reason reason, ResidentRecord resident) {
        return decision(action, reason, resident.profileId(), null);
    }

    @Nonnull
    private Decision decision(Action action,
                              Reason reason,
                              @Nullable String profileId,
                              @Nullable String operationId) {
        return decision(action, reason, profileId, operationId, null, null);
    }

    @Nonnull
    private Decision decision(Action action,
                              Reason reason,
                              @Nullable String profileId,
                              @Nullable String operationId,
                              @Nullable UUID requiredLiveProjectionUuid,
                              @Nullable UUID staleAliasUuid) {
        return new Decision(
                action,
                reason,
                profileId,
                operationId,
                requiredLiveProjectionUuid,
                staleAliasUuid);
    }

    /**
     * Validates that a separately observed retained projection is the exact linked replacement for
     * one guarded historical-alias decision.
     */
    public static boolean exactRetainedProjectionProof(
            @Nonnull Decision guardedSuppression,
            @Nonnull Observation staleObservation,
            @Nonnull Observation retainedObservation,
            @Nullable Decision retainedDecision) {
        Objects.requireNonNull(guardedSuppression, "guardedSuppression");
        Objects.requireNonNull(staleObservation, "staleObservation");
        Objects.requireNonNull(retainedObservation, "retainedObservation");
        return retainedDecision != null
                && retainedDecision.action() == Action.ALLOW
                && guardedSuppression.requiredLiveProjectionUuid() != null
                && guardedSuppression.requiredLiveProjectionUuid()
                        .equals(retainedObservation.npcUuid())
                && Objects.equals(guardedSuppression.profileId(), retainedDecision.profileId())
                && (guardedSuppression.operationId() == null
                        || guardedSuppression.operationId()
                        .equals(retainedDecision.operationId()))
                && staleObservation.npcUuid().equals(retainedDecision.staleAliasUuid())
                && retainedDecision.requiredLiveProjectionUuid() == null;
    }

    public enum Action {
        IGNORE,
        ALLOW,
        DEFER,
        SUPPRESS
    }

    public enum Reason {
        UNRELATED_NPC,
        UNTRUSTED_COMPOSITE,
        CONFLICTING_EVIDENCE,
        AUTHORITY_NOT_CURRENTLY_MANAGED,
        ACTIVE_CAPTURE_SOURCE,
        INVALID_CAPTURE_PROJECTION,
        UNSUPPORTED_OPERATION,
        RELEASE_NOT_SPAWN_VISIBLE,
        RELEASE_TARGET_MISMATCH,
        RELEASE_RESIDENT_MISMATCH,
        INVALID_RELEASE_MARKER,
        ACTIVE_RELEASE_PROJECTION,
        HOUSED_ALIAS,
        RESIDENT_IDENTITY_MISMATCH,
        RELEASE_OPERATION_MISSING,
        BLOCKED_RESIDENT_STATE,
        HISTORICAL_RESIDENT_ALIAS,
        DEPLOYED_IDENTITY_MISMATCH,
        INVALID_DEPLOYED_MARKER,
        DEPLOYED_IMPORT_ADOPTION,
        DEPLOYED_RELEASE_PROJECTION,
        ORPHAN_MANAGED_MARKER
    }

    /** Immutable projection marker values copied from the ECS component. */
    public record MarkerEvidence(@Nullable String profileId,
                                 @Nullable String operationId,
                                 @Nullable String projectionKind,
                                 @Nullable String slotKey,
                                  @Nullable UUID sourceNpcUuid,
                                  long generation) {
        boolean indicatesManagedCoop() {
            String normalizedKind = projectionKind == null
                    ? null
                    : projectionKind.trim().toUpperCase(Locale.ROOT);
            if (normalizedKind != null && !normalizedKind.isEmpty()) {
                return normalizedKind.startsWith("MANAGED_COOP_");
            }
            return (operationId != null
                    && operationId.toLowerCase(Locale.ROOT).startsWith("managed-coop-"))
                    || (slotKey != null && sourceNpcUuid != null);
        }
    }

    /** Immutable identity observation created by the synchronous ECS add callback. */
    public record Observation(@Nonnull UUID npcUuid,
                              @Nullable MarkerEvidence marker) {
        public Observation {
            Objects.requireNonNull(npcUuid, "npcUuid");
        }

        @Nonnull
        public static Observation of(@Nonnull UUID npcUuid,
                                     @Nullable MarkerEvidence marker) {
            return new Observation(npcUuid, marker);
        }
    }

    /** One non-destructive-by-default runtime action with stable diagnostic identity only. */
    public record Decision(@Nonnull Action action,
                           @Nonnull Reason reason,
                           @Nullable String profileId,
                           @Nullable String operationId,
                           @Nullable UUID requiredLiveProjectionUuid,
                           @Nullable UUID staleAliasUuid) {
        public Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(reason, "reason");
            if (requiredLiveProjectionUuid != null && staleAliasUuid != null) {
                throw new IllegalArgumentException(
                        "a decision cannot guard and retire aliases simultaneously");
            }
        }

        public Decision(Action action,
                        Reason reason,
                        @Nullable String profileId,
                        @Nullable String operationId) {
            this(action, reason, profileId, operationId, null, null);
        }
    }

    private record Evidence(@Nullable ResidentRecord resident,
                            @Nullable OperationRecord operation,
                            boolean mapped,
                            boolean conflicting) {
    }
}

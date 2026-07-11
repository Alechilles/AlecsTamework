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
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure, fail-closed authority policy for NPCs mapped to managed-coop resident lifecycles.
 *
 * <p>The policy reads only immutable runtime index snapshots. A live entity is allowed solely when
 * it is the exact durable release target and its immutable projection marker proves the same
 * profile, operation, source, authority slot, and release-claim generation. Housed residents,
 * capture sources, historical aliases, and ambiguous evidence are suppressed.</p>
 */
public final class ManagedCoopStaleEntityPolicy {
    private static final String MANAGED_RELEASE_KIND = "MANAGED_COOP_RELEASE";
    private static final Pattern RELEASE_OPERATION_ID = Pattern.compile(
            "managed-coop-release:[0-9a-f]{64}"
    );
    private static final long FINALIZED_RELEASE_MARKER_GENERATION = 1L;

    private final ManagedCoopResidentIndex residentIndex;
    private final ManagedCoopLifecycleOperationIndex operationIndex;
    private final BooleanSupplier compositeTrust;

    public ManagedCoopStaleEntityPolicy(
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull BooleanSupplier compositeTrust) {
        this.residentIndex = Objects.requireNonNull(residentIndex, "residentIndex");
        this.operationIndex = Objects.requireNonNull(operationIndex, "operationIndex");
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
            return suppress(Reason.UNTRUSTED_COMPOSITE, evidence);
        }
        if (evidence.conflicting()) {
            return suppress(Reason.CONFLICTING_EVIDENCE, evidence);
        }
        if (evidence.operation() != null) {
            return decideActiveOperation(observation, evidence);
        }
        if (evidence.resident() != null) {
            return decideResident(observation, evidence.resident());
        }
        return suppress(Reason.ORPHAN_MANAGED_MARKER, evidence);
    }

    @Nonnull
    private Decision decideActiveOperation(Observation observation, Evidence evidence) {
        OperationRecord operation = evidence.operation();
        if (operation.kind() == OperationKind.CAPTURE) {
            return decision(
                    Action.SUPPRESS,
                    Objects.equals(observation.npcUuid(), operation.sourceNpcUuid())
                            ? Reason.ACTIVE_CAPTURE_SOURCE
                            : Reason.INVALID_CAPTURE_PROJECTION,
                    operation.profileId(),
                    operation.operationId()
            );
        }
        if (operation.kind() != OperationKind.RELEASE) {
            return suppress(Reason.UNSUPPORTED_OPERATION, evidence);
        }
        return decideActiveRelease(observation, evidence.resident(), operation);
    }

    @Nonnull
    private Decision decideActiveRelease(Observation observation,
                                         @Nullable ResidentRecord resident,
                                         OperationRecord operation) {
        if (!isSpawnVisibleReleaseState(operation.state())) {
            return operationDecision(Action.SUPPRESS, Reason.RELEASE_NOT_SPAWN_VISIBLE, operation);
        }
        if (!observation.npcUuid().equals(operation.plannedTargetUuid())
                || operation.actualTargetUuid() != null
                && !operation.plannedTargetUuid().equals(operation.actualTargetUuid())) {
            return operationDecision(Action.SUPPRESS, Reason.RELEASE_TARGET_MISMATCH, operation);
        }
        if (!matchesReleasingResident(resident, operation)) {
            return operationDecision(Action.SUPPRESS, Reason.RELEASE_RESIDENT_MISMATCH, operation);
        }
        long markerGeneration = expectedActiveReleaseMarkerGeneration(operation);
        if (!matchesReleaseMarker(
                observation.marker(), operation.operationId(), operation.profileId(),
                operation.authorityKey().slotKey(operation.residentSlot()),
                resident.sourceNpcUuid(), markerGeneration)) {
            return operationDecision(Action.SUPPRESS, Reason.INVALID_RELEASE_MARKER, operation);
        }
        return operationDecision(Action.ALLOW, Reason.ACTIVE_RELEASE_PROJECTION, operation);
    }

    @Nonnull
    private Decision decideResident(Observation observation, ResidentRecord resident) {
        if (resident.state() == ResidentState.HOUSED) {
            return residentDecision(Action.SUPPRESS, Reason.HOUSED_ALIAS, resident);
        }
        if (resident.state() == ResidentState.RELEASING) {
            return residentDecision(Action.SUPPRESS, Reason.RELEASE_OPERATION_MISSING, resident);
        }
        if (resident.state() != ResidentState.DEPLOYED) {
            return residentDecision(Action.SUPPRESS, Reason.BLOCKED_RESIDENT_STATE, resident);
        }
        if (resident.deployedNpcUuid() == null
                || !observation.npcUuid().equals(resident.deployedNpcUuid())
                || !observation.npcUuid().equals(resident.residentUuid())) {
            return residentDecision(Action.SUPPRESS, Reason.HISTORICAL_RESIDENT_ALIAS, resident);
        }
        if (!matchesFinalizedReleaseMarker(observation.marker(), resident)) {
            return residentDecision(Action.SUPPRESS, Reason.INVALID_DEPLOYED_MARKER, resident);
        }
        return residentDecision(Action.ALLOW, Reason.DEPLOYED_RELEASE_PROJECTION, resident);
    }

    private boolean matchesFinalizedReleaseMarker(@Nullable MarkerEvidence marker,
                                                  ResidentRecord resident) {
        if (marker == null || !canonicalReleaseOperationId(marker.operationId())) {
            return false;
        }
        return matchesReleaseMarker(
                marker,
                marker.operationId(),
                resident.profileId(),
                resident.authorityKey().slotKey(resident.residentSlot()),
                resident.sourceNpcUuid(),
                FINALIZED_RELEASE_MARKER_GENERATION
        ) && resident.sourceNpcUuid() != null
                && !resident.sourceNpcUuid().equals(resident.deployedNpcUuid());
    }

    private boolean matchesReleaseMarker(@Nullable MarkerEvidence marker,
                                         String operationId,
                                         String profileId,
                                         String slotKey,
                                         @Nullable UUID sourceNpcUuid,
                                         long generation) {
        return marker != null
                && canonicalText(marker.operationId())
                && canonicalText(marker.profileId())
                && MANAGED_RELEASE_KIND.equals(marker.projectionKind())
                && operationId.equals(marker.operationId())
                && profileId.equals(marker.profileId())
                && slotKey.equals(marker.slotKey())
                && sourceNpcUuid != null
                && sourceNpcUuid.equals(marker.sourceNpcUuid())
                && marker.generation() == generation;
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

    private boolean canonicalReleaseOperationId(@Nullable String operationId) {
        return operationId != null && RELEASE_OPERATION_ID.matcher(operationId).matches();
    }

    private boolean canonicalText(@Nullable String value) {
        return value != null && !value.isBlank() && value.equals(value.trim());
    }

    @Nonnull
    private Decision suppress(Reason reason, Evidence evidence) {
        String profileId = evidence.operation() != null
                ? evidence.operation().profileId()
                : evidence.resident() != null ? evidence.resident().profileId() : null;
        String operationId = evidence.operation() != null
                ? evidence.operation().operationId()
                : null;
        return decision(Action.SUPPRESS, reason, profileId, operationId);
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
        return new Decision(action, reason, profileId, operationId);
    }

    public enum Action {
        IGNORE,
        ALLOW,
        SUPPRESS
    }

    public enum Reason {
        UNRELATED_NPC,
        UNTRUSTED_COMPOSITE,
        CONFLICTING_EVIDENCE,
        ACTIVE_CAPTURE_SOURCE,
        INVALID_CAPTURE_PROJECTION,
        UNSUPPORTED_OPERATION,
        RELEASE_NOT_SPAWN_VISIBLE,
        RELEASE_TARGET_MISMATCH,
        RELEASE_RESIDENT_MISMATCH,
        INVALID_RELEASE_MARKER,
        ACTIVE_RELEASE_PROJECTION,
        HOUSED_ALIAS,
        RELEASE_OPERATION_MISSING,
        BLOCKED_RESIDENT_STATE,
        HISTORICAL_RESIDENT_ALIAS,
        INVALID_DEPLOYED_MARKER,
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
            return (projectionKind != null
                    && projectionKind.toUpperCase(Locale.ROOT).startsWith("MANAGED_COOP_"))
                    || (operationId != null
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

    /** One fail-closed runtime action with stable diagnostic identity only. */
    public record Decision(@Nonnull Action action,
                           @Nonnull Reason reason,
                           @Nullable String profileId,
                           @Nullable String operationId) {
        public Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private record Evidence(@Nullable ResidentRecord resident,
                            @Nullable OperationRecord operation,
                            boolean mapped,
                            boolean conflicting) {
    }
}

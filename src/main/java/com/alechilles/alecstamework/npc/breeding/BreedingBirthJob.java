package com.alechilles.alecstamework.npc.breeding;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Immutable snapshot of one admitted breeding birth job.
 *
 * <p>The initial admission is retained for idempotent replay and diagnostics. The active admission
 * shrinks as capacity is rechecked and each child succeeds or is abandoned. Updated snapshots
 * replace prior snapshots inside {@link BreedingBirthJobRegistry}; callers never mutate registry
 * state.
 */
public record BreedingBirthJob(
        @Nonnull UUID jobId,
        @Nonnull BreedingPairKey pairKey,
        @Nonnull BreedingParentIdentity firstParent,
        @Nonnull BreedingParentIdentity secondParent,
        @Nonnull BreedingPopulationAdmissionService.BreedingMode mode,
        @Nonnull BreedingBirthPlan plan,
        @Nonnull BreedingJobAdmission initialAdmission,
        @Nonnull BreedingJobAdmission activeAdmission,
        @Nonnull ParentBreedingSnapshot firstParentSnapshot,
        @Nonnull ParentBreedingSnapshot secondParentSnapshot,
        @Nonnull AppliedCooldownFingerprint firstParentCooldownFingerprint,
        @Nonnull AppliedCooldownFingerprint secondParentCooldownFingerprint,
        @Nonnull BreedingBirthAnchor anchor,
        @Nonnull BreedingBirthJobState state) {
    public BreedingBirthJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(pairKey, "pairKey");
        Objects.requireNonNull(firstParent, "firstParent");
        Objects.requireNonNull(secondParent, "secondParent");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(initialAdmission, "initialAdmission");
        Objects.requireNonNull(activeAdmission, "activeAdmission");
        Objects.requireNonNull(firstParentSnapshot, "firstParentSnapshot");
        Objects.requireNonNull(secondParentSnapshot, "secondParentSnapshot");
        Objects.requireNonNull(firstParentCooldownFingerprint, "firstParentCooldownFingerprint");
        Objects.requireNonNull(secondParentCooldownFingerprint, "secondParentCooldownFingerprint");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(state, "state");
        if (!pairKey.firstParentUuid().equals(firstParent.entityUuid())
                || !pairKey.secondParentUuid().equals(secondParent.entityUuid())) {
            throw new IllegalArgumentException("Parent identities must match canonical pair order");
        }
        if (firstParent.profileId().equals(secondParent.profileId())) {
            throw new IllegalArgumentException("A breeding pair requires two distinct profiles");
        }
        if (!BreedingJobAdmission.isOrderedSubsequence(plan.children(), initialAdmission.children())) {
            throw new IllegalArgumentException("Initial admission must preserve birth-plan order");
        }
        if (!BreedingJobAdmission.isOrderedSubsequence(initialAdmission.children(), activeAdmission.children())) {
            throw new IllegalArgumentException("Active admission must only shrink the initial admission");
        }
        if (!initialAdmission.reservation().scope().equals(activeAdmission.reservation().scope())) {
            throw new IllegalArgumentException("Active admission must preserve reservation scopes");
        }
    }

    /** Creates a reserved job while canonicalizing caller parent order. */
    @Nonnull
    public static BreedingBirthJob reserved(@Nonnull UUID jobId,
                                             @Nonnull String worldId,
                                             @Nonnull BreedingParentIdentity parentA,
                                             @Nonnull BreedingParentIdentity parentB) {
        BreedingBirthPlan emptyPlan = BreedingBirthPlan.of(List.of());
        return reserved(
                jobId,
                worldId,
                parentA,
                parentB,
                BreedingPopulationAdmissionService.BreedingMode.PASSIVE,
                emptyPlan,
                BreedingJobAdmission.of(List.of(), BreedingReservationScope.unscoped()),
                ParentBreedingSnapshot.empty(),
                ParentBreedingSnapshot.empty(),
                AppliedCooldownFingerprint.none(),
                AppliedCooldownFingerprint.none(),
                BreedingBirthAnchor.origin()
        );
    }

    /**
     * Creates a fully admitted reserved job while preserving parent-associated state through
     * canonical UUID ordering.
     */
    @Nonnull
    public static BreedingBirthJob reserved(
            @Nonnull UUID jobId,
            @Nonnull String worldId,
            @Nonnull BreedingParentIdentity parentA,
            @Nonnull BreedingParentIdentity parentB,
            @Nonnull BreedingPopulationAdmissionService.BreedingMode mode,
            @Nonnull BreedingBirthPlan plan,
            @Nonnull BreedingJobAdmission admission,
            @Nonnull ParentBreedingSnapshot parentASnapshot,
            @Nonnull ParentBreedingSnapshot parentBSnapshot,
            @Nonnull AppliedCooldownFingerprint parentAFingerprint,
            @Nonnull AppliedCooldownFingerprint parentBFingerprint,
            @Nonnull BreedingBirthAnchor anchor) {
        Objects.requireNonNull(parentA, "parentA");
        Objects.requireNonNull(parentB, "parentB");
        BreedingPairKey pairKey = BreedingPairKey.of(worldId, parentA.entityUuid(), parentB.entityUuid());
        boolean parentAFirst = pairKey.firstParentUuid().equals(parentA.entityUuid());
        return new BreedingBirthJob(
                jobId,
                pairKey,
                parentAFirst ? parentA : parentB,
                parentAFirst ? parentB : parentA,
                mode,
                plan,
                admission,
                admission,
                parentAFirst ? parentASnapshot : parentBSnapshot,
                parentAFirst ? parentBSnapshot : parentASnapshot,
                parentAFirst ? parentAFingerprint : parentBFingerprint,
                parentAFirst ? parentBFingerprint : parentAFingerprint,
                anchor,
                BreedingBirthJobState.RESERVED
        );
    }

    /** Current ordered children whose exact reservations remain outstanding. */
    @Nonnull
    public List<PlannedChild> admittedChildren() {
        return activeAdmission.children();
    }

    /** Original ordered children admitted before spawn-time shrink. */
    @Nonnull
    public List<PlannedChild> initiallyAdmittedChildren() {
        return initialAdmission.children();
    }

    /** Current exact outstanding population reservation. */
    @Nonnull
    public BreedingBirthReservation reservation() {
        return activeAdmission.reservation();
    }

    /** Original exact reservation retained for replay and diagnostics. */
    @Nonnull
    public BreedingBirthReservation initialReservation() {
        return initialAdmission.reservation();
    }

    @Nonnull
    BreedingBirthJob withState(@Nonnull BreedingBirthJobState nextState) {
        return copy(activeAdmission, nextState);
    }

    @Nonnull
    BreedingBirthJob shrinkAdmission(@Nonnull List<PlannedChild> retainedChildren) {
        return copy(activeAdmission.shrinkTo(retainedChildren), state);
    }

    @Nonnull
    BreedingBirthJob releaseChildReservation(@Nonnull PlannedChild child) {
        return copy(activeAdmission.release(child), state);
    }

    @Nonnull
    BreedingBirthJob withTerminalState(@Nonnull BreedingBirthJobState terminalState) {
        if (!terminalState.isTerminal()) {
            throw new IllegalArgumentException("terminalState must be terminal");
        }
        return copy(activeAdmission.emptyCopy(), terminalState);
    }

    boolean hasSameIdentity(BreedingBirthJob other) {
        return other != null
                && jobId.equals(other.jobId)
                && pairKey.equals(other.pairKey)
                && firstParent.equals(other.firstParent)
                && secondParent.equals(other.secondParent)
                && mode == other.mode
                && plan.equals(other.plan)
                && initialAdmission.equals(other.initialAdmission)
                && firstParentSnapshot.equals(other.firstParentSnapshot)
                && secondParentSnapshot.equals(other.secondParentSnapshot)
                && firstParentCooldownFingerprint.equals(other.firstParentCooldownFingerprint)
                && secondParentCooldownFingerprint.equals(other.secondParentCooldownFingerprint)
                && anchor.equals(other.anchor);
    }

    @Nonnull
    BreedingActiveReservation activeReservationSnapshot() {
        return new BreedingActiveReservation(jobId, pairKey.worldId(), mode, anchor, reservation());
    }

    private BreedingBirthJob copy(BreedingJobAdmission nextActiveAdmission,
                                  BreedingBirthJobState nextState) {
        return new BreedingBirthJob(
                jobId,
                pairKey,
                firstParent,
                secondParent,
                mode,
                plan,
                initialAdmission,
                nextActiveAdmission,
                firstParentSnapshot,
                secondParentSnapshot,
                firstParentCooldownFingerprint,
                secondParentCooldownFingerprint,
                anchor,
                nextState
        );
    }
}

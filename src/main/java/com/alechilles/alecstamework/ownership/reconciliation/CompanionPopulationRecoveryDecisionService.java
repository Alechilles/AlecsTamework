package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Pure decision table for distinguishing applied, unapplied, and ambiguous journal outcomes. */
final class CompanionPopulationRecoveryDecisionService {
    private CompanionPopulationRecoveryDecisionService() {
    }

    @Nonnull
    static Decision decide(@Nonnull CompanionPopulationOperationRecord.State operationState,
                           @Nonnull Context context,
                           @Nullable ObservedState observed,
                           @Nullable ObservedState previousObserved) {
        if (observed == null) {
            if (operationState == CompanionPopulationOperationRecord.State.APPLYING
                    && previousObserved != null
                    && matches(previousObserved, context.oldState(), context.oldPhysical(), true)) {
                return Decision.close("startup-recovery-old-source-state-observed");
            }
            if (operationState == CompanionPopulationOperationRecord.State.COMPENSATING
                    && context.operation() == OwnerPopulationOperation.BREEDING
                    && context.oldState().ownerUuid() == null) {
                return Decision.close("startup-recovery-breeding-compensation-target-absent");
            }
            if (isAppliedPhase(operationState) && isPermanentRelease(context)) {
                return Decision.commit("startup-recovery-permanent-release-target-absent");
            }
            if (canRetryAbsentBreedingTarget(operationState, context)) {
                return Decision.retry("startup-recovery-breeding-target-absent");
            }
            if (canCloseAbsentTarget(context)) {
                return Decision.close("startup-recovery-target-absent");
            }
            return Decision.ambiguous("operation-recovery-target-not-observed");
        }
        if (operationState == CompanionPopulationOperationRecord.State.COMPENSATING) {
            return matches(observed, context.oldState(), context.oldPhysical(), false)
                    ? Decision.close("startup-recovery-compensation-observed")
                    : Decision.ambiguous("operation-recovery-compensation-incomplete");
        }
        if (isAppliedPhase(operationState)
                && context.permanentDeath()
                && observed.physical()) {
            if (observed.deathObserved()
                    && observed.ownerObserved()
                    && observed.ownerUuid() == null) {
                return Decision.commit("startup-recovery-permanent-death-observed");
            }
            return Decision.ambiguous("operation-recovery-permanent-death-target-still-physical");
        }
        if (isAppliedPhase(operationState)
                && isPermanentRelease(context)
                && observed.ownerObserved()
                && observed.ownerUuid() == null) {
            return Decision.commit("startup-recovery-permanent-release-ownerless");
        }
        if (isAppliedPhase(operationState)
                && context.operation() == OwnerPopulationOperation.BREEDING
                && observed.physical()
                && targetCompatible(context, observed)) {
            return Decision.commit("startup-recovery-breeding-child-observed");
        }

        UUID oldOwner = context.oldState().ownerUuid();
        UUID newOwner = context.newState().ownerUuid();
        return Objects.equals(oldOwner, newOwner)
                ? decideSameOwner(context, observed)
                : decideOwnerDelta(context, observed);
    }

    @Nonnull
    private static Decision decideOwnerDelta(@Nonnull Context context,
                                             @Nonnull ObservedState observed) {
        if (!observed.ownerObserved()) {
            return Decision.ambiguous("operation-recovery-owner-not-observed");
        }
        if (Objects.equals(observed.ownerUuid(), context.newState().ownerUuid())) {
            return targetCompatible(context, observed)
                    ? Decision.commit()
                    : Decision.ambiguous("operation-recovery-target-state-mismatch");
        }
        if (Objects.equals(observed.ownerUuid(), context.oldState().ownerUuid())) {
            return matches(observed, context.oldState(), context.oldPhysical(), false)
                    ? Decision.close("startup-recovery-old-state-observed")
                    : Decision.ambiguous("operation-recovery-partial-apply");
        }
        return Decision.ambiguous("operation-recovery-owner-ambiguous");
    }

    @Nonnull
    private static Decision decideSameOwner(@Nonnull Context context,
                                            @Nonnull ObservedState observed) {
        boolean oldMatches = matches(observed, context.oldState(), context.oldPhysical(), false);
        if (context.newState().lifecycleSpecified() || context.newState().worldSpecified()) {
            boolean targetMatches = targetCompatible(context, observed);
            if (targetMatches && !oldMatches) {
                return Decision.commit();
            }
            if (oldMatches && !targetMatches) {
                return Decision.close("startup-recovery-old-state-observed");
            }
            if (targetMatches && statesEquivalent(context.oldState(), context.newState())) {
                return Decision.close("startup-recovery-noop-observed");
            }
            return Decision.ambiguous("operation-recovery-same-owner-ambiguous");
        }

        return switch (context.operation()) {
            case RESTORE -> observed.physical()
                    ? Decision.commit()
                    : oldMatches
                    ? Decision.close("startup-recovery-old-state-observed")
                    : Decision.ambiguous("operation-recovery-restore-ambiguous");
            case REHOME -> {
                if (observed.physical() && matchesTargetLocation(context, observed)) {
                    yield Decision.commit();
                }
                yield oldMatches
                        ? Decision.close("startup-recovery-old-state-observed")
                        : Decision.ambiguous("operation-recovery-rehome-ambiguous");
            }
            case LIFECYCLE_CHANGE -> oldMatches
                    ? Decision.close("startup-recovery-old-state-observed")
                    : Decision.commit();
            default -> oldMatches
                    ? Decision.close("startup-recovery-old-state-observed")
                    : Decision.ambiguous("operation-recovery-no-owner-delta");
        };
    }

    private static boolean targetCompatible(@Nonnull Context context,
                                            @Nonnull ObservedState observed) {
        return matches(observed, context.newState(), context.targetPhysical(), true)
                && (context.operation() != OwnerPopulationOperation.REHOME
                || matchesTargetLocation(context, observed));
    }

    private static boolean matches(@Nonnull ObservedState observed,
                                   @Nonnull JournalState expected,
                                   @Nullable PhysicalExpectation physical,
                                   boolean target) {
        if (!Objects.equals(observed.ownerUuid(), expected.ownerUuid())) {
            return false;
        }
        if (expected.lifecycleSpecified()
                && !lifecycleMatches(expected.lifecycleState(), observed)) {
            return false;
        }
        if (expected.worldSpecified()) {
            if (expected.worldName() != null && !expected.worldName().equals(observed.worldName())) {
                return false;
            }
            if (expected.worldName() == null && observed.ownerUuid() != null && target) {
                return false;
            }
        }
        if (physical != null && observed.physical()) {
            return physical.matches(observed);
        }
        return physical == null || !target || !observed.physical();
    }

    private static boolean lifecycleMatches(@Nullable CompanionLifecycleState expected,
                                            @Nonnull ObservedState observed) {
        if (expected == null) {
            return true;
        }
        if (expected == CompanionLifecycleState.ACTIVE) {
            return observed.physical()
                    && (observed.lifecycleState() == CompanionLifecycleState.ACTIVE
                    || observed.lifecycleState() == CompanionLifecycleState.UNLOADED);
        }
        return expected == observed.lifecycleState();
    }

    private static boolean matchesTargetLocation(@Nonnull Context context,
                                                 @Nonnull ObservedState observed) {
        PhysicalExpectation target = context.targetPhysical();
        return target == null || (observed.physical() && target.matches(observed));
    }

    private static boolean statesEquivalent(@Nonnull JournalState oldState,
                                            @Nonnull JournalState newState) {
        return Objects.equals(oldState.ownerUuid(), newState.ownerUuid())
                && (!oldState.lifecycleSpecified() || !newState.lifecycleSpecified()
                || oldState.lifecycleState() == newState.lifecycleState())
                && (!oldState.worldSpecified() || !newState.worldSpecified()
                || Objects.equals(oldState.worldName(), newState.worldName()));
    }

    private static boolean canCloseAbsentTarget(@Nonnull Context context) {
        return context.oldState().ownerUuid() == null
                && context.newState().ownerUuid() != null
                && (context.operation() == OwnerPopulationOperation.NEW_OWNERSHIP
                || context.operation() == OwnerPopulationOperation.LEGACY_ADOPTION);
    }

    private static boolean canRetryAbsentBreedingTarget(
            @Nonnull CompanionPopulationOperationRecord.State operationState,
            @Nonnull Context context
    ) {
        return isAppliedPhase(operationState)
                && context.operation() == OwnerPopulationOperation.BREEDING
                && context.oldState().ownerUuid() == null;
    }

    private static boolean isAppliedPhase(CompanionPopulationOperationRecord.State state) {
        return state == CompanionPopulationOperationRecord.State.APPLYING
                || state == CompanionPopulationOperationRecord.State.APPLIED;
    }

    private static boolean isPermanentRelease(@Nonnull Context context) {
        return context.permanentRelease()
                && context.operation() == OwnerPopulationOperation.OWNER_CLEAR
                && context.oldState().ownerUuid() != null
                && context.newState().ownerUuid() == null
                && context.newState().lifecycleSpecified()
                && context.newState().lifecycleState() == CompanionLifecycleState.RELEASED;
    }

    record Context(@Nonnull OwnerPopulationOperation operation,
                   @Nonnull JournalState oldState,
                   @Nonnull JournalState newState,
                   @Nullable PhysicalExpectation targetPhysical,
                   @Nullable PhysicalExpectation oldPhysical,
                   boolean permanentRelease,
                   boolean permanentDeath) {
    }

    record JournalState(@Nullable UUID ownerUuid,
                        @Nullable CompanionLifecycleState lifecycleState,
                        boolean lifecycleSpecified,
                        @Nullable String worldName,
                        boolean worldSpecified) {
    }

    record ObservedState(@Nullable UUID ownerUuid,
                         boolean ownerObserved,
                         @Nonnull CompanionLifecycleState lifecycleState,
                         @Nullable String worldName,
                         boolean physical,
                         boolean deathObserved,
                         @Nullable Integer chunkX,
                         @Nullable Integer chunkZ) {
    }

    record PhysicalExpectation(@Nonnull String worldName, int chunkX, int chunkZ) {
        private boolean matches(@Nonnull ObservedState observed) {
            return worldName.equals(observed.worldName())
                    && Objects.equals(chunkX, observed.chunkX())
                    && Objects.equals(chunkZ, observed.chunkZ());
        }
    }

    enum Outcome {
        COMMIT,
        CLOSE,
        RETRY,
        AMBIGUOUS
    }

    record Decision(@Nonnull Outcome outcome, @Nonnull String reason) {
        @Nonnull
        private static Decision commit() {
            return new Decision(Outcome.COMMIT, "startup-recovery-applied-state-observed");
        }

        @Nonnull
        private static Decision commit(@Nonnull String reason) {
            return new Decision(Outcome.COMMIT, reason);
        }

        @Nonnull
        private static Decision close(@Nonnull String reason) {
            return new Decision(Outcome.CLOSE, reason);
        }

        @Nonnull
        private static Decision retry(@Nonnull String reason) {
            return new Decision(Outcome.RETRY, reason);
        }

        @Nonnull
        private static Decision ambiguous(@Nonnull String reason) {
            return new Decision(Outcome.AMBIGUOUS, reason);
        }
    }
}

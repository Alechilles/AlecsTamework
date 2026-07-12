package com.alechilles.alecstamework.ownership;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Continuations for an admitted owner mutation, including its freshly resolved live target. */
public interface OwnerMutationCallbacks {
    default void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
    }

    default void onPopulationDenied(@Nonnull CompanionPopulationPreparationResult result) {
    }

    default boolean beforeApply(@Nonnull String profileId) {
        return true;
    }

    default boolean beforeApply(@Nonnull String profileId, @Nonnull OwnerMutationContext context) {
        return beforeApply(profileId);
    }

    default void onApplyCompensated(@Nonnull String profileId, @Nonnull String reason) {
    }

    default void onApplyCompensated(@Nonnull String profileId,
                                    @Nonnull String reason,
                                    @Nonnull OwnerMutationContext context) {
        onApplyCompensated(profileId, reason);
    }

    default void onApplied(@Nonnull OwnerPopulationDecision decision) {
    }

    default void onApplied(@Nonnull OwnerPopulationDecision decision, @Nonnull String profileId) {
        onApplied(decision);
    }

    default void onApplied(@Nonnull OwnerPopulationDecision decision,
                           @Nonnull String profileId,
                           @Nonnull OwnerMutationContext context) {
        onApplied(decision, profileId);
    }

    default void onCommitted(@Nonnull OwnerPopulationCommitResult result) {
    }

    default void onPopulationCommitted(@Nonnull CompanionPopulationCommitResult result) {
    }

    /**
     * Performs thread-safe, state-independent terminal cleanup when a world no longer accepts a
     * deferred callback. Implementations must not access live ECS or player state from this hook.
     * A non-null commit is the persistence result observed after the live mutation was applied.
     */
    default void onWorldDispatchRejected(@Nonnull String reason,
                                         boolean mutationApplied,
                                         @Nullable CompanionPopulationCommitResult commit) {
    }

    default void onDurabilityDegraded(@Nonnull String reason) {
    }
}

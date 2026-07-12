package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Projects a revivable live death into dormant population state exactly once per observation. */
final class CompanionDeathPopulationProjector {
    private final DormantObserver observer;

    CompanionDeathPopulationProjector(@Nonnull CompanionPopulationRuntimeReconciler reconciler) {
        this(Objects.requireNonNull(reconciler, "reconciler")::observeDormant);
    }

    CompanionDeathPopulationProjector(@Nonnull DormantObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    boolean observeRevivableDeath(
            @Nonnull CompanionPopulationEntityObservation observation,
            boolean supportsRevive
    ) {
        Objects.requireNonNull(observation, "observation");
        if (!supportsRevive || observation.ownerUuid() == null) {
            return false;
        }
        observer.observe(
                observation.npcUuid(),
                observation.ownerUuid(),
                observation.worldName(),
                CompanionLifecycleState.DEAD_REVIVABLE,
                "ecs-death-component"
        );
        return true;
    }

    @FunctionalInterface
    interface DormantObserver {
        void observe(
                UUID npcUuid,
                UUID ownerUuid,
                String ownershipWorldName,
                CompanionLifecycleState lifecycleState,
                String source
        );
    }
}

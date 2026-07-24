package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Publishes released dormant-recorded API events after canonical publication. */
@FunctionalInterface
public interface DormantCompanionEventSink {
    void publish(@Nonnull Published event);

    /** Immutable post-publication event input with no live ECS or player objects. */
    record Published(
            @Nonnull DormantCompanionObservation observation,
            @Nonnull DormantCompanionEventFacts facts,
            @Nonnull CompanionProfileReadModel canonicalProfile,
            long emittedAtMs
    ) {
        public Published {
            Objects.requireNonNull(observation, "Dormant observation is required");
            Objects.requireNonNull(facts, "Dormant event facts are required");
            Objects.requireNonNull(
                    canonicalProfile, "Canonical dormant profile is required"
            );
        }
    }
}

package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import javax.annotation.Nonnull;

/** Classifies canonical lifecycle states for named weighted capacity domains. */
public final class PopulationDomainLifecycleClassifier {
    private PopulationDomainLifecycleClassifier() {
    }

    /** Returns the exact owned/deployable classification used by domain admission. */
    @Nonnull
    public static Classification classify(@Nonnull LifecycleState state) {
        if (state == null) {
            throw new IllegalArgumentException("Lifecycle state is required");
        }
        return switch (state) {
            case ACTIVE, UNLOADED, COOP, LOST, UNRESOLVED ->
                    new Classification(true, true);
            case CAPTURED, ROSTER_STORED, PROVISIONED_DORMANT, DEAD_REVIVABLE ->
                    new Classification(true, false);
            case RELEASED -> new Classification(false, false);
        };
    }

    /** Immutable capacity classification for one lifecycle state. */
    public record Classification(boolean owned, boolean deployable) {
    }
}

package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import javax.annotation.Nonnull;

/** Fixed group-count semantics over the canonical replacement lifecycle vocabulary. */
public final class PopulationGroupLifecycleClassifier {
    private PopulationGroupLifecycleClassifier() {
    }

    public static boolean consumesOwned(@Nonnull LifecycleState state) {
        if (state == null) {
            throw new IllegalArgumentException("Lifecycle state is required");
        }
        return state != LifecycleState.RELEASED;
    }

    public static boolean consumesActive(@Nonnull LifecycleState state) {
        if (state == null) {
            throw new IllegalArgumentException("Lifecycle state is required");
        }
        return state == LifecycleState.ACTIVE
                || state == LifecycleState.UNLOADED;
    }
}


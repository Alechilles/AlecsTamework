package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.Objects;

/** Fixed, non-configurable owned/active group-count semantics. */
public final class PopulationGroupLifecycleClassifier {
    private PopulationGroupLifecycleClassifier() {
    }

    public static boolean consumesOwned(CompanionLifecycleState lifecycle) {
        return Objects.requireNonNull(lifecycle, "lifecycle") != CompanionLifecycleState.RELEASED;
    }

    public static boolean consumesActive(CompanionLifecycleState lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        return lifecycle == CompanionLifecycleState.ACTIVE
                || lifecycle == CompanionLifecycleState.UNLOADED
                || lifecycle == CompanionLifecycleState.RESTORING;
    }
}

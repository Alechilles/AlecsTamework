package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;

/** Builds active in-memory indexes after initial assets load and before runtime construction. */
public final class TameworkActiveAssetInitializer {
    private TameworkActiveAssetInitializer() {
    }

    /** Runs only the index initializers owned by active modules. */
    public static void initialize(
            TameworkRuntimeActivationPlan plan,
            Runnable genericPersistence,
            Runnable capture,
            Runnable bondedPersistence
    ) {
        if (plan.isActive(TameworkRuntimeModule.GENERIC_PERSISTENCE)) {
            genericPersistence.run();
        }
        if (plan.isActive(TameworkRuntimeModule.CAPTURE)) {
            capture.run();
        }
        if (plan.isActive(TameworkRuntimeModule.BONDED_PERSISTENCE)) {
            bondedPersistence.run();
        }
    }
}

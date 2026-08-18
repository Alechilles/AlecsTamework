package com.alechilles.alecstamework.persistence.activation;

import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation
        .TameworkRuntimeModule;
import java.util.Objects;

/** Pure startup gate shared by generic and bonded persistence compositions. */
public final class TameworkPersistenceActivationGate {
    private TameworkPersistenceActivationGate() {
    }

    /**
     * Returns whether a mutating authority may be constructed.
     *
     * <p>Active content can create a new empty store. Existing durable
     * evidence can force recovery even when content was removed. Read-only
     * evidence always wins and never permits writer initialization.</p>
     */
    public static boolean shouldConstruct(
            TameworkRuntimeActivationPlan plan,
            TameworkPersistenceActivationEvidence evidence,
            TameworkRuntimeModule module
    ) {
        Objects.requireNonNull(plan, "Activation plan is required");
        Objects.requireNonNull(evidence, "Persistence evidence is required");
        Objects.requireNonNull(module, "Runtime module is required");
        if (evidence.readOnly()) {
            return false;
        }
        return plan.isActive(module) || evidence.hasDurableWork();
    }
}

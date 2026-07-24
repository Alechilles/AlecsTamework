package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import javax.annotation.Nonnull;

/**
 * Supplies the released role/global enablement decision for free death restoration.
 *
 * <p>Lost recovery remains free and immediate and therefore does not consult this policy.</p>
 */
@FunctionalInterface
public interface ReleasedRestorationPolicy {
    /**
     * Returns the current combined role and runtime enablement decision for a dead profile.
     */
    boolean deadRestorationEnabled(
            @Nonnull CompanionProfileReadModel profile
    );
}

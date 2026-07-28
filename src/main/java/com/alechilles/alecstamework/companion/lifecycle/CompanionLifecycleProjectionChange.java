package com.alechilles.alecstamework.companion.lifecycle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Self-contained before/after lifecycle evidence for rebuildable projection consumers.
 *
 * @param before canonical lifecycle before the operation, or null on creation
 * @param after canonical lifecycle committed by the operation
 */
public record CompanionLifecycleProjectionChange(
        @Nullable CompanionLifecycle before,
        @Nonnull CompanionLifecycle after
) {
    public CompanionLifecycleProjectionChange {
        if (after == null) {
            throw new IllegalArgumentException(
                    "Lifecycle projection requires committed after state"
            );
        }
        if (before != null
                && (!before.profileId().equals(after.profileId())
                || after.revision().value() <= before.revision().value())) {
            throw new IllegalArgumentException(
                    "Lifecycle projection must advance one profile"
            );
        }
    }
}

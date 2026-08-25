package com.alechilles.alecstamework.api.commandhud;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Session-scoped producer of detached target HUD contributor data. */
public interface CommandTargetHudSessionContributor extends AutoCloseable {
    /** Composes one complete namespaced contribution from the detached base. */
    @Nonnull
    CommandHudContribution compose(
            @Nonnull CommandTargetHudSnapshot base,
            @Nullable CommandHudContribution previous,
            @Nonnull CommandHudDirtyScope scope
    );

    /** Releases contributor-local state when the HUD session closes. */
    @Override
    default void close() {
    }
}

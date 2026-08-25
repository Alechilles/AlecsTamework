package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Internal session adapter shared by the typed target and hotswap routes. */
interface CommandHudContributorSession<B> extends AutoCloseable {
    /** Composes one detached contribution for the supplied base snapshot. */
    @Nonnull
    CommandHudContribution compose(
            @Nonnull B base,
            @Nullable CommandHudContribution previous,
            @Nonnull CommandHudDirtyScope scope
    );

    /** Releases contributor-local state when its HUD session closes. */
    @Override
    default void close() {
    }
}

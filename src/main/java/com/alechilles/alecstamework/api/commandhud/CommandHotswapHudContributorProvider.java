package com.alechilles.alecstamework.api.commandhud;

import javax.annotation.Nonnull;

/** Factory for one hotswap contributor per active equipped-tool session. */
public interface CommandHotswapHudContributorProvider {
    /** Creates a session-scoped hotswap contributor. */
    @Nonnull
    CommandHotswapHudSessionContributor create(
            @Nonnull CommandHudContributorCreateContext context);
}

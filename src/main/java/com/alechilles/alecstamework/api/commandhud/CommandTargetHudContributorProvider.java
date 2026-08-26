package com.alechilles.alecstamework.api.commandhud;

import javax.annotation.Nonnull;

/** Factory for one target contributor per active target HUD session. */
public interface CommandTargetHudContributorProvider {
    /** Creates a session-scoped target contributor. */
    @Nonnull
    CommandTargetHudSessionContributor create(
            @Nonnull CommandHudContributorCreateContext context);
}

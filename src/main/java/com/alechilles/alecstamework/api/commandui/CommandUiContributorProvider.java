package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;

/** Factory for one session-scoped contributor instance per opened command UI. */
@FunctionalInterface
public interface CommandUiContributorProvider {
    /** Creates a contributor from detached session data and a guarded dirty sink. */
    @Nonnull
    CommandUiSessionContributor create(@Nonnull CommandUiContributorCreateContext context);
}

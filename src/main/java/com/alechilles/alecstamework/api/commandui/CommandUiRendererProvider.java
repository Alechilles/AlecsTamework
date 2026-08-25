package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;

/** Factory for one detached command UI page controller per renderer session. */
@FunctionalInterface
public interface CommandUiRendererProvider {
    /** Creates a controller for one custom command UI session. */
    @Nonnull
    CommandUiPageController<?> create(@Nonnull CommandUiOpenContext context);
}

package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;

/**
 * Factory for one command-menu controller per opened Tamework UI session.
 *
 * <p>The context is detached presentation data. It does not expose a player,
 * ECS reference, store, mutable item, or gameplay callback.</p>
 */
@FunctionalInterface
public interface CommandUiProvider {
    /** Creates a controller for one command-menu session. */
    CommandUiPageController<?> create(@Nonnull CommandUiOpenContext context);
}

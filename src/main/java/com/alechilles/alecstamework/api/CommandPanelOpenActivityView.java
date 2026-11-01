package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Typed payload for a player opening a command item's panel, such as right-clicking with a flute.
 */
public record CommandPanelOpenActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull UUID playerId
) implements ActivityView {
    public CommandPanelOpenActivityView {
        header = Objects.requireNonNull(header, "header");
        playerId = Objects.requireNonNull(playerId, "playerId");
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.MANAGED_CARE_PRODUCTION;
    }

    @Override
    @Nonnull
    public CommandPanelOpenActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new CommandPanelOpenActivityView(nextHeader, playerId);
    }
}

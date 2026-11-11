package com.alechilles.alecstamework.api.access;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One thing an owner shares with: a named player or an opted-in group.
 *
 * @param playerUuid the named player, or null for a group grant
 * @param playerName the player's name when the grant was made; may be null
 * @param groupId    the {@link AccessGroupProvider#id()}, or null for a player grant
 * @param level      the level granted, before the server's highest allowed level is applied
 */
public record AccessGrantView(@Nullable UUID playerUuid,
                              @Nullable String playerName,
                              @Nullable String groupId,
                              @Nonnull AccessLevel level) {
    /** True for a grant to a group rather than a named player. */
    public boolean isGroup() {
        return groupId != null;
    }
}

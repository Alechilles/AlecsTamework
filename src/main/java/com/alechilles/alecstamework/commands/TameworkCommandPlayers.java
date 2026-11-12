package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves a player named in a command as a UUID or an online player's name. Reads player identity
 * metadata only (no live {@code Player} components), so it is safe on any thread.
 */
final class TameworkCommandPlayers {
    private TameworkCommandPlayers() {
    }

    /** A named player: the UUID, and the name when that player is online. */
    record Named(@Nonnull UUID uuid, @Nullable String name) {
    }

    /**
     * {@code raw} as a UUID (online or not), else as the name of an online player, ignoring case;
     * null when it is neither.
     */
    @Nullable
    static Named resolve(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        Universe universe = Universe.get();
        try {
            UUID uuid = UUID.fromString(value);
            PlayerRef online = universe == null ? null : universe.getPlayer(uuid);
            return new Named(uuid, OwnerNameUtil.resolve(online));
        } catch (IllegalArgumentException notUuid) {
            // Fall through to a name.
        }
        if (universe == null) {
            return null;
        }
        for (PlayerRef player : universe.getPlayers()) {
            if (value.equalsIgnoreCase(player.getUsername()) && player.getUuid() != null) {
                return new Named(player.getUuid(), OwnerNameUtil.resolve(player));
            }
        }
        return null;
    }
}

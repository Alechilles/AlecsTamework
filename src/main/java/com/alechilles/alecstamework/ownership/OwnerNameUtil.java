package com.alechilles.alecstamework.ownership;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Resolves player display names for owner storage and messaging.
 */
public final class OwnerNameUtil {
    private OwnerNameUtil() {
    }

    public static String resolve(Player player) {
        if (player == null) {
            return null;
        }
        // Prefer display name, then fall back to account username.
        String displayName = normalize(player.getDisplayName());
        if (displayName != null) {
            return displayName;
        }
        PlayerRef playerRef = player.getPlayerRef();
        return resolve(playerRef);
    }

    public static String resolve(PlayerRef playerRef) {
        if (playerRef == null) {
            return null;
        }
        return normalize(playerRef.getUsername());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}

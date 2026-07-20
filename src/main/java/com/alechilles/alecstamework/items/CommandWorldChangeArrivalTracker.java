package com.alechilles.alecstamework.items;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Tracks the exact destination where a player-add callback must authorize world-change travel. */
final class CommandWorldChangeArrivalTracker {
    private final ConcurrentHashMap<UUID, String> destinationsByPlayer = new ConcurrentHashMap<>();

    void mark(@Nullable UUID playerUuid, @Nullable String destinationWorldName) {
        if (playerUuid == null || destinationWorldName == null || destinationWorldName.isBlank()) {
            return;
        }
        destinationsByPlayer.put(playerUuid, destinationWorldName);
    }

    boolean consume(@Nullable UUID playerUuid, @Nullable String destinationWorldName) {
        if (playerUuid == null || destinationWorldName == null || destinationWorldName.isBlank()) {
            return false;
        }
        return destinationsByPlayer.remove(playerUuid, destinationWorldName);
    }

    void clear(@Nullable UUID playerUuid) {
        if (playerUuid != null) {
            destinationsByPlayer.remove(playerUuid);
        }
    }
}

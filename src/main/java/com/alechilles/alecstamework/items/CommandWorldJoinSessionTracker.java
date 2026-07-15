package com.alechilles.alecstamework.items;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Distinguishes a connection's initial world join from later portal/world transitions. */
final class CommandWorldJoinSessionTracker {
    private final Set<UUID> awaitingInitialWorld = ConcurrentHashMap.newKeySet();

    void onConnected(@Nullable UUID playerUuid) {
        if (playerUuid != null) {
            awaitingInitialWorld.add(playerUuid);
        }
    }

    void onDisconnected(@Nullable UUID playerUuid) {
        if (playerUuid != null) {
            awaitingInitialWorld.remove(playerUuid);
        }
    }

    /** Returns true only for a world addition after this connection's initial world became ready. */
    boolean isWorldChange(@Nonnull UUID playerUuid) {
        return !awaitingInitialWorld.remove(playerUuid);
    }
}

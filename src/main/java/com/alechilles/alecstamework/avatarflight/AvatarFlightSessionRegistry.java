package com.alechilles.alecstamework.avatarflight;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * Tracks transformed-player flight sessions across packet, command, and disconnect paths.
 */
public final class AvatarFlightSessionRegistry {
    private static final Set<UUID> ACTIVE_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> DISCONNECTING_PLAYERS = ConcurrentHashMap.newKeySet();

    private AvatarFlightSessionRegistry() {
    }

    public static void markActive(@Nonnull UUID playerUuid) {
        DISCONNECTING_PLAYERS.remove(playerUuid);
        ACTIVE_PLAYERS.add(playerUuid);
    }

    public static void markInactive(@Nonnull UUID playerUuid) {
        ACTIVE_PLAYERS.remove(playerUuid);
        DISCONNECTING_PLAYERS.remove(playerUuid);
    }

    public static void markDisconnecting(@Nonnull UUID playerUuid) {
        ACTIVE_PLAYERS.remove(playerUuid);
        DISCONNECTING_PLAYERS.add(playerUuid);
    }

    public static boolean isActive(@Nonnull UUID playerUuid) {
        return ACTIVE_PLAYERS.contains(playerUuid) && !DISCONNECTING_PLAYERS.contains(playerUuid);
    }

    public static boolean isDisconnecting(@Nonnull UUID playerUuid) {
        return DISCONNECTING_PLAYERS.contains(playerUuid);
    }
}

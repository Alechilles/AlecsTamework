package com.alechilles.alecstamework.items;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caches whether each player may currently be holding a command item for the inspector HUD.
 */
public final class CommandTargetHudActivationTracker {
    private static final long INACTIVE_SANITY_SCAN_INTERVAL_MS = 1_000L;

    private final Map<UUID, HandState> statesByPlayer = new HashMap<>();
    private final LinkedHashSet<UUID> candidatePlayers = new LinkedHashSet<>();

    boolean shouldInspectPlayer(@Nullable UUID playerUuid, long nowMs) {
        if (playerUuid == null) {
            return false;
        }
        HandState state = statesByPlayer.get(playerUuid);
        if (state == null) {
            return true;
        }
        return shouldInspectForTests(
                state.dirty(),
                state.commandItem(),
                state.lastResolvedMs(),
                nowMs,
                INACTIVE_SANITY_SCAN_INTERVAL_MS
        );
    }

    @Nullable
    String cachedCommandItemId(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        HandState state = statesByPlayer.get(playerUuid);
        return state != null && state.commandItem() ? state.activeItemId() : null;
    }

    boolean isDirty(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        HandState state = statesByPlayer.get(playerUuid);
        return state == null || state.dirty();
    }

    void markDirty(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        candidatePlayers.add(playerUuid);
        HandState previous = statesByPlayer.get(playerUuid);
        statesByPlayer.put(playerUuid, new HandState(
                previous != null ? previous.activeItemId() : null,
                previous != null && previous.commandItem(),
                true,
                previous != null ? previous.lastResolvedMs() : 0L
        ));
    }

    void recordResolvedHand(@Nullable UUID playerUuid,
                            @Nullable String activeItemId,
                            boolean commandItem,
                            long nowMs) {
        if (playerUuid == null) {
            return;
        }
        if (commandItem) {
            candidatePlayers.add(playerUuid);
        } else {
            candidatePlayers.remove(playerUuid);
        }
        statesByPlayer.put(playerUuid, new HandState(activeItemId, commandItem, false, nowMs));
    }

    void remove(@Nullable UUID playerUuid) {
        if (playerUuid != null) {
            candidatePlayers.remove(playerUuid);
            statesByPlayer.remove(playerUuid);
        }
    }

    List<UUID> candidatePlayerUuids() {
        return List.copyOf(candidatePlayers);
    }

    static boolean shouldInspectForTests(boolean dirty,
                                         boolean commandItem,
                                         long lastResolvedMs,
                                         long nowMs,
                                         long inactiveSanityScanIntervalMs) {
        if (dirty || commandItem) {
            return true;
        }
        return nowMs - lastResolvedMs >= Math.max(0L, inactiveSanityScanIntervalMs);
    }

    private record HandState(@Nullable String activeItemId,
                             boolean commandItem,
                             boolean dirty,
                             long lastResolvedMs) {
        private HandState {
            if (!commandItem) {
                activeItemId = null;
            }
        }
    }
}

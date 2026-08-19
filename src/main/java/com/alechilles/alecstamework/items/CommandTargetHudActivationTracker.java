package com.alechilles.alecstamework.items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caches whether each player may currently be holding a command item for the inspector HUD.
 * Event systems and the HUD tick service may touch this tracker from different runtime paths, so
 * access is synchronized around the shared hand-state map and candidate queues.
 */
public final class CommandTargetHudActivationTracker implements CommandHudDirtySink {
    private static final long INACTIVE_SANITY_SCAN_INTERVAL_MS = 1_000L;

    private final Map<UUID, HandState> statesByPlayer = new HashMap<>();
    private final ArrayDeque<UUID> dirtyQueue = new ArrayDeque<>();
    private final HashSet<UUID> dirtyQueued = new HashSet<>();
    private final ArrayDeque<UUID> activeQueue = new ArrayDeque<>();
    private final HashSet<UUID> activePlayers = new HashSet<>();

    synchronized boolean shouldInspectPlayer(@Nullable UUID playerUuid, long nowMs) {
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
    synchronized String cachedCommandItemId(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        HandState state = statesByPlayer.get(playerUuid);
        return state != null && state.commandItem() ? state.activeItemId() : null;
    }

    synchronized boolean isDirty(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        HandState state = statesByPlayer.get(playerUuid);
        return state == null || state.dirty();
    }

    @Override
    public synchronized void markDirty(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        if (dirtyQueued.add(playerUuid)) {
            dirtyQueue.addLast(playerUuid);
        }
        HandState previous = statesByPlayer.get(playerUuid);
        statesByPlayer.put(playerUuid, new HandState(
                previous != null ? previous.activeItemId() : null,
                previous != null && previous.commandItem(),
                true,
                previous != null ? previous.lastResolvedMs() : 0L
        ));
    }

    synchronized void recordResolvedHand(@Nullable UUID playerUuid,
                                         @Nullable String activeItemId,
                                         boolean commandItem,
                                         long nowMs) {
        if (playerUuid == null) {
            return;
        }
        HandState previous = statesByPlayer.get(playerUuid);
        boolean wasActive = previous != null && previous.commandItem();
        if (commandItem && !wasActive) {
            if (activePlayers.add(playerUuid)) {
                enqueueActivePlayer(playerUuid, previous != null && previous.dirty());
            }
        } else if (!commandItem) {
            activePlayers.remove(playerUuid);
            activeQueue.remove(playerUuid);
        }
        statesByPlayer.put(playerUuid, new HandState(activeItemId, commandItem, false, nowMs));
    }

    synchronized void remove(@Nullable UUID playerUuid) {
        if (playerUuid != null) {
            dirtyQueued.remove(playerUuid);
            dirtyQueue.remove(playerUuid);
            activePlayers.remove(playerUuid);
            activeQueue.remove(playerUuid);
            statesByPlayer.remove(playerUuid);
        }
    }

    synchronized List<UUID> candidatePlayerUuids() {
        LinkedHashSet<UUID> candidates = new LinkedHashSet<>();
        for (UUID playerUuid : dirtyQueue) {
            if (dirtyQueued.contains(playerUuid) && statesByPlayer.containsKey(playerUuid)) {
                candidates.add(playerUuid);
            }
        }
        for (UUID playerUuid : activeQueue) {
            if (activePlayers.contains(playerUuid)) {
                candidates.add(playerUuid);
            }
        }
        return List.copyOf(candidates);
    }

    synchronized CandidateBatch selectCandidateBatch(int maxCandidates) {
        if (maxCandidates <= 0) {
            return CandidateBatch.EMPTY;
        }
        ArrayList<UUID> selected = new ArrayList<>(Math.min(maxCandidates, dirtyQueue.size() + activeQueue.size()));
        HashSet<UUID> selectedSet = new HashSet<>();
        while (selected.size() < maxCandidates && !dirtyQueue.isEmpty()) {
            UUID playerUuid = dirtyQueue.removeFirst();
            if (!dirtyQueued.remove(playerUuid) || !statesByPlayer.containsKey(playerUuid)) {
                continue;
            }
            if (selectedSet.add(playerUuid)) {
                selected.add(playerUuid);
            }
        }

        int activeQueueSize = activeQueue.size();
        for (int inspected = 0;
             inspected < activeQueueSize && selected.size() < maxCandidates;
             inspected++) {
            UUID playerUuid = activeQueue.removeFirst();
            if (!activePlayers.contains(playerUuid)) {
                continue;
            }
            activeQueue.addLast(playerUuid);
            if (selectedSet.add(playerUuid)) {
                selected.add(playerUuid);
            }
        }
        return new CandidateBatch(List.copyOf(selected));
    }

    private void enqueueActivePlayer(@Nonnull UUID playerUuid, boolean wasDirty) {
        // Keep a newly resolved dirty player ahead of the previous tail on the next rotation.
        if (!wasDirty || activeQueue.isEmpty()) {
            activeQueue.addLast(playerUuid);
            return;
        }
        UUID currentTail = activeQueue.removeLast();
        activeQueue.addLast(playerUuid);
        activeQueue.addLast(currentTail);
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

    record CandidateBatch(@Nonnull List<UUID> playerUuids) {
        private static final CandidateBatch EMPTY = new CandidateBatch(List.of());
    }
}

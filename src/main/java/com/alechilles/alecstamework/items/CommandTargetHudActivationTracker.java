package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Caches whether each player may currently be holding a command item for the inspector HUD.
 * Event systems and HUD tick services may touch this tracker from different runtime paths, so
 * access is synchronized around store-scoped hand-state maps and candidate queues.
 */
public final class CommandTargetHudActivationTracker implements CommandHudDirtySink {
    private static final long INACTIVE_SANITY_SCAN_INTERVAL_MS = 1_000L;

    private final Map<Store<EntityStore>, QueueState> statesByStore = new IdentityHashMap<>();
    private final QueueState unscopedState = new QueueState();
    private final List<LifecycleListener> lifecycleListeners = new ArrayList<>();

    synchronized boolean shouldInspectPlayer(@Nullable UUID playerUuid, long nowMs) {
        return shouldInspectPlayer(null, playerUuid, nowMs);
    }

    synchronized boolean shouldInspectPlayer(@Nullable Store<EntityStore> store,
                                             @Nullable UUID playerUuid,
                                             long nowMs) {
        if (playerUuid == null) {
            return false;
        }
        return stateFor(store).shouldInspectPlayer(
                playerUuid,
                nowMs,
                INACTIVE_SANITY_SCAN_INTERVAL_MS
        );
    }

    @Nullable
    synchronized String cachedCommandItemId(@Nullable UUID playerUuid) {
        return cachedCommandItemId(null, playerUuid);
    }

    @Nullable
    synchronized String cachedCommandItemId(@Nullable Store<EntityStore> store,
                                            @Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        return stateFor(store).cachedCommandItemId(playerUuid);
    }

    synchronized boolean isDirty(@Nullable UUID playerUuid) {
        return isDirty(null, playerUuid);
    }

    synchronized boolean isDirty(@Nullable Store<EntityStore> store,
                                 @Nullable UUID playerUuid) {
        return playerUuid != null && stateFor(store).isDirty(playerUuid);
    }

    @Override
    public synchronized void markDirty(@Nullable UUID playerUuid) {
        markDirty(null, playerUuid);
    }

    @Override
    public synchronized void markDirty(@Nullable Store<EntityStore> store,
                                       @Nullable UUID playerUuid) {
        if (playerUuid != null) {
            stateFor(store).markDirty(playerUuid);
        }
    }

    synchronized void recordResolvedHand(@Nullable UUID playerUuid,
                                         @Nullable String activeItemId,
                                         boolean commandItem,
                                         long nowMs) {
        recordResolvedHand(null, playerUuid, activeItemId, commandItem, nowMs);
    }

    synchronized void recordResolvedHand(@Nullable Store<EntityStore> store,
                                         @Nullable UUID playerUuid,
                                         @Nullable String activeItemId,
                                         boolean commandItem,
                                         long nowMs) {
        if (playerUuid != null) {
            stateFor(store).recordResolvedHand(playerUuid, activeItemId, commandItem, nowMs);
        }
    }

    synchronized void remove(@Nullable UUID playerUuid) {
        remove(null, playerUuid);
    }

    @Override
    public synchronized void remove(@Nullable Store<EntityStore> store,
                                    @Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        QueueState state = existingState(store);
        if (state != null) {
            state.remove(playerUuid);
            removeEmptyState(store, state);
        }
        if (store != null) {
            notifyPlayerRemoved(store, playerUuid);
        }
    }

    @Override
    public synchronized void removeStore(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return;
        }
        statesByStore.remove(store);
        notifyStoreRemoved(store);
    }

    synchronized List<UUID> candidatePlayerUuids() {
        return candidatePlayerUuids(null);
    }

    synchronized List<UUID> candidatePlayerUuids(@Nullable Store<EntityStore> store) {
        return stateFor(store).candidatePlayerUuids();
    }

    synchronized CandidateBatch selectCandidateBatch(int maxCandidates) {
        return selectCandidateBatchForTests(maxCandidates, Long.MAX_VALUE, 0L, 0);
    }

    synchronized CandidateBatch selectCandidateBatch(@Nonnull Store<EntityStore> store,
                                                      int maxCandidates,
                                                      long nowMs,
                                                      long activeRefreshIntervalMs,
                                                      int reservedActiveCapacity) {
        return stateFor(store).selectCandidateBatch(
                maxCandidates,
                nowMs,
                activeRefreshIntervalMs,
                reservedActiveCapacity
        );
    }

    synchronized CandidateBatch selectCandidateBatch(@Nonnull Store<EntityStore> store,
                                                      int maxCandidates) {
        return selectCandidateBatch(store, maxCandidates, Long.MAX_VALUE, 0L, 0);
    }

    synchronized CandidateBatch selectCandidateBatchForTests(int maxCandidates,
                                                              long nowMs,
                                                              long activeRefreshIntervalMs,
                                                              int reservedActiveCapacity) {
        return stateFor(null).selectCandidateBatch(
                maxCandidates,
                nowMs,
                activeRefreshIntervalMs,
                reservedActiveCapacity
        );
    }

    synchronized void addLifecycleListener(@Nonnull LifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    private QueueState stateFor(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return unscopedState;
        }
        return statesByStore.computeIfAbsent(store, ignored -> new QueueState());
    }

    @Nullable
    private QueueState existingState(@Nullable Store<EntityStore> store) {
        return store == null ? unscopedState : statesByStore.get(store);
    }

    private void removeEmptyState(@Nullable Store<EntityStore> store, @Nonnull QueueState state) {
        if (store != null && state.isEmpty()) {
            statesByStore.remove(store, state);
        }
    }

    private void notifyPlayerRemoved(@Nonnull Store<EntityStore> store,
                                     @Nonnull UUID playerUuid) {
        for (LifecycleListener listener : List.copyOf(lifecycleListeners)) {
            listener.onPlayerRemoved(store, playerUuid);
        }
    }

    private void notifyStoreRemoved(@Nonnull Store<EntityStore> store) {
        for (LifecycleListener listener : List.copyOf(lifecycleListeners)) {
            listener.onStoreRemoved(store);
        }
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

    /** Receives store-aware lifecycle cleanup after a player or store leaves the ECS runtime. */
    interface LifecycleListener {
        void onPlayerRemoved(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid);

        void onStoreRemoved(@Nonnull Store<EntityStore> store);
    }

    private static final class QueueState {
        private final Map<UUID, HandState> statesByPlayer = new HashMap<>();
        private final ArrayDeque<UUID> dirtyQueue = new ArrayDeque<>();
        private final HashSet<UUID> dirtyQueued = new HashSet<>();
        private final ArrayDeque<UUID> activeQueue = new ArrayDeque<>();
        private final HashSet<UUID> activePlayers = new HashSet<>();

        private boolean shouldInspectPlayer(@Nonnull UUID playerUuid,
                                             long nowMs,
                                             long inactiveSanityScanIntervalMs) {
            HandState state = statesByPlayer.get(playerUuid);
            if (state == null) {
                return true;
            }
            return CommandTargetHudActivationTracker.shouldInspectForTests(
                    state.dirty(),
                    state.commandItem(),
                    state.lastResolvedMs(),
                    nowMs,
                    inactiveSanityScanIntervalMs
            );
        }

        @Nullable
        private String cachedCommandItemId(@Nonnull UUID playerUuid) {
            HandState state = statesByPlayer.get(playerUuid);
            return state != null && state.commandItem() ? state.activeItemId() : null;
        }

        private boolean isDirty(@Nonnull UUID playerUuid) {
            HandState state = statesByPlayer.get(playerUuid);
            return state == null || state.dirty();
        }

        private void markDirty(@Nonnull UUID playerUuid) {
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

        private void recordResolvedHand(@Nonnull UUID playerUuid,
                                        @Nullable String activeItemId,
                                        boolean commandItem,
                                        long nowMs) {
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

        private void remove(@Nonnull UUID playerUuid) {
            dirtyQueued.remove(playerUuid);
            dirtyQueue.remove(playerUuid);
            activePlayers.remove(playerUuid);
            activeQueue.remove(playerUuid);
            statesByPlayer.remove(playerUuid);
        }

        private boolean isEmpty() {
            return statesByPlayer.isEmpty()
                    && dirtyQueue.isEmpty()
                    && dirtyQueued.isEmpty()
                    && activeQueue.isEmpty()
                    && activePlayers.isEmpty();
        }

        @Nonnull
        private List<UUID> candidatePlayerUuids() {
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

        @Nonnull
        private CandidateBatch selectCandidateBatch(int maxCandidates,
                                                     long nowMs,
                                                     long activeRefreshIntervalMs,
                                                     int reservedActiveCapacity) {
            if (maxCandidates <= 0) {
                return CandidateBatch.EMPTY;
            }
            ArrayList<UUID> selected = new ArrayList<>(Math.min(
                    maxCandidates,
                    dirtyQueue.size() + activeQueue.size()
            ));
            HashSet<UUID> selectedSet = new HashSet<>();
            boolean dirtyBacklog = !dirtyQueue.isEmpty();
            int activeReserve = dirtyBacklog && !activeQueue.isEmpty()
                    ? Math.min(maxCandidates, Math.max(0, reservedActiveCapacity))
                    : 0;
            drainDirty(selected, selectedSet, maxCandidates - activeReserve);

            int activeQueueSize = activeQueue.size();
            for (int inspected = 0;
                 inspected < activeQueueSize && selected.size() < maxCandidates;
                 inspected++) {
                UUID playerUuid = activeQueue.removeFirst();
                if (!activePlayers.contains(playerUuid)) {
                    continue;
                }
                activeQueue.addLast(playerUuid);
                boolean activeDue = isActiveDue(playerUuid, nowMs, activeRefreshIntervalMs);
                if (dirtyQueued.contains(playerUuid)) {
                    if (!activeDue) {
                        continue;
                    }
                    // The active slot supersedes a stale fallback marker. Leave its queue node in
                    // place; drainDirty will skip it in O(1) when it reaches the queue head.
                    dirtyQueued.remove(playerUuid);
                }
                if (!activeDue) {
                    continue;
                }
                if (selectedSet.add(playerUuid)) {
                    selected.add(playerUuid);
                }
            }

            if (selected.size() < maxCandidates) {
                drainDirty(selected, selectedSet, maxCandidates);
            }
            return new CandidateBatch(List.copyOf(selected));
        }

        private void drainDirty(@Nonnull ArrayList<UUID> selected,
                                @Nonnull HashSet<UUID> selectedSet,
                                int limit) {
            while (selected.size() < limit && !dirtyQueue.isEmpty()) {
                UUID playerUuid = dirtyQueue.removeFirst();
                if (!dirtyQueued.remove(playerUuid) || !statesByPlayer.containsKey(playerUuid)) {
                    continue;
                }
                if (selectedSet.add(playerUuid)) {
                    selected.add(playerUuid);
                }
            }
        }

        private boolean isActiveDue(@Nonnull UUID playerUuid,
                                    long nowMs,
                                    long activeRefreshIntervalMs) {
            if (activeRefreshIntervalMs <= 0L) {
                return true;
            }
            HandState state = statesByPlayer.get(playerUuid);
            return state == null || nowMs - state.lastResolvedMs() >= activeRefreshIntervalMs;
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

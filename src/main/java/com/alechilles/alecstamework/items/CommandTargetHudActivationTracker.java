package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
    private static final int MAX_QUEUE_INSPECTION = 32;
    private static final long INACTIVE_RECOVERY_INTERVAL_MS = 5_000L;

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
        return stateFor(store).shouldInspectPlayer(playerUuid);
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

    synchronized void markRecovery(@Nullable UUID playerUuid) {
        markRecovery(null, playerUuid);
    }

    @Override
    public synchronized void markRecovery(@Nullable Store<EntityStore> store,
                                           @Nullable UUID playerUuid) {
        if (playerUuid != null) {
            stateFor(store).markRecovery(playerUuid);
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

    /** Receives store-aware lifecycle cleanup after a player or store leaves the ECS runtime. */
    interface LifecycleListener {
        void onPlayerRemoved(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid);

        void onStoreRemoved(@Nonnull Store<EntityStore> store);
    }

    private static final class QueueState {
        private final Map<UUID, HandState> statesByPlayer = new HashMap<>();
        private final LinkedHashSet<UUID> dirtyPlayers = new LinkedHashSet<>();
        private final LinkedHashSet<UUID> activePlayers = new LinkedHashSet<>();
        private final LinkedHashSet<UUID> recoveryPlayers = new LinkedHashSet<>();
        private final Map<UUID, Long> recoveryDueMs = new HashMap<>();
        private final HashSet<UUID> recoverySelected = new HashSet<>();

        private boolean shouldInspectPlayer(@Nonnull UUID playerUuid) {
            HandState state = statesByPlayer.get(playerUuid);
            if (state == null) {
                return true;
            }
            return state.dirty() || state.commandItem() || recoverySelected.contains(playerUuid);
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
            recoveryPlayers.remove(playerUuid);
            recoveryDueMs.remove(playerUuid);
            recoverySelected.remove(playerUuid);
            dirtyPlayers.add(playerUuid);
            HandState previous = statesByPlayer.get(playerUuid);
            statesByPlayer.put(playerUuid, new HandState(
                    previous != null ? previous.activeItemId() : null,
                    previous != null && previous.commandItem(),
                    true,
                    previous != null ? previous.lastResolvedMs() : 0L
            ));
        }

        private void markRecovery(@Nonnull UUID playerUuid) {
            if (recoveryPlayers.contains(playerUuid)
                    || recoverySelected.contains(playerUuid)
                    || activePlayers.contains(playerUuid)) {
                return;
            }
            HandState previous = statesByPlayer.get(playerUuid);
            if (previous != null && (previous.commandItem() || previous.dirty())) {
                return;
            }
            if (previous == null) {
                statesByPlayer.put(playerUuid, new HandState(null, false, false, 0L));
            }
            recoveryPlayers.add(playerUuid);
            recoveryDueMs.put(playerUuid, 0L);
        }

        private void recordResolvedHand(@Nonnull UUID playerUuid,
                                        @Nullable String activeItemId,
                                        boolean commandItem,
                                        long nowMs) {
            HandState previous = statesByPlayer.get(playerUuid);
            if (commandItem) {
                recoveryPlayers.remove(playerUuid);
                recoveryDueMs.remove(playerUuid);
                recoverySelected.remove(playerUuid);
                activePlayers.add(playerUuid);
            } else {
                activePlayers.remove(playerUuid);
                recoverySelected.remove(playerUuid);
            }
            statesByPlayer.put(playerUuid, new HandState(activeItemId, commandItem, false, nowMs));
            if (!commandItem) {
                enqueueRecovery(playerUuid, recoveryDueAt(nowMs));
            }
        }

        private void remove(@Nonnull UUID playerUuid) {
            dirtyPlayers.remove(playerUuid);
            activePlayers.remove(playerUuid);
            recoveryPlayers.remove(playerUuid);
            recoveryDueMs.remove(playerUuid);
            recoverySelected.remove(playerUuid);
            statesByPlayer.remove(playerUuid);
        }

        private boolean isEmpty() {
            return statesByPlayer.isEmpty()
                    && dirtyPlayers.isEmpty()
                    && activePlayers.isEmpty()
                    && recoveryPlayers.isEmpty()
                    && recoverySelected.isEmpty();
        }

        @Nonnull
        private List<UUID> candidatePlayerUuids() {
            LinkedHashSet<UUID> candidates = new LinkedHashSet<>();
            addSnapshotCandidates(candidates, dirtyPlayers);
            addSnapshotCandidates(candidates, activePlayers);
            addSnapshotCandidates(candidates, recoveryPlayers);
            return List.copyOf(candidates);
        }

        private void addSnapshotCandidates(@Nonnull LinkedHashSet<UUID> candidates,
                                           @Nonnull LinkedHashSet<UUID> players) {
            int inspected = 0;
            for (UUID playerUuid : players) {
                if (inspected++ >= MAX_QUEUE_INSPECTION) {
                    break;
                }
                if (statesByPlayer.containsKey(playerUuid)) {
                    candidates.add(playerUuid);
                }
            }
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
                    dirtyPlayers.size() + activePlayers.size() + recoveryPlayers.size()
            ));
            HashSet<UUID> selectedSet = new HashSet<>();
            boolean dirtyBacklog = !dirtyPlayers.isEmpty();
            // An explicit dirty event has priority when the caller allows only one candidate.
            // Otherwise retain one slot for a due recovery head before filling dirty or active work.
            int recoveryReserve = hasDueRecovery(nowMs) && (!dirtyBacklog || maxCandidates > 1)
                    ? 1
                    : 0;
            int activeReserve = dirtyBacklog && !activePlayers.isEmpty()
                    ? Math.min(
                            Math.max(0, maxCandidates - recoveryReserve),
                            Math.max(0, reservedActiveCapacity)
                    )
                    : 0;
            int preRecoveryLimit = maxCandidates - recoveryReserve;
            drainDirty(selected, selectedSet, Math.max(0, preRecoveryLimit - activeReserve));

            int activeLimit = preRecoveryLimit;
            if (dirtyBacklog && activeReserve > 0 && !dirtyPlayers.isEmpty()) {
                activeLimit = Math.min(preRecoveryLimit, selected.size() + activeReserve);
            }
            selectActive(
                    selected,
                    selectedSet,
                    activeLimit,
                    nowMs,
                    activeRefreshIntervalMs
            );

            if (selected.size() < preRecoveryLimit) {
                drainDirty(selected, selectedSet, preRecoveryLimit);
            }
            if (selected.size() < maxCandidates) {
                selectRecovery(selected, selectedSet, maxCandidates, nowMs);
            }
            return new CandidateBatch(List.copyOf(selected));
        }

        private void drainDirty(@Nonnull ArrayList<UUID> selected,
                                @Nonnull HashSet<UUID> selectedSet,
                                int limit) {
            int inspected = 0;
            while (selected.size() < limit
                    && inspected++ < MAX_QUEUE_INSPECTION
                    && !dirtyPlayers.isEmpty()) {
                UUID playerUuid = pollFirst(dirtyPlayers);
                if (playerUuid == null || !statesByPlayer.containsKey(playerUuid)) {
                    continue;
                }
                if (selectedSet.add(playerUuid)) {
                    selected.add(playerUuid);
                }
            }
        }

        private void selectActive(@Nonnull ArrayList<UUID> selected,
                                  @Nonnull HashSet<UUID> selectedSet,
                                  int limit,
                                  long nowMs,
                                  long activeRefreshIntervalMs) {
            int inspected = 0;
            while (selected.size() < limit
                    && inspected++ < MAX_QUEUE_INSPECTION
                    && !activePlayers.isEmpty()) {
                UUID playerUuid = pollFirst(activePlayers);
                if (playerUuid == null) {
                    continue;
                }
                HandState state = statesByPlayer.get(playerUuid);
                if (state == null || !state.commandItem()) {
                    continue;
                }
                boolean activeDue = isActiveDue(playerUuid, nowMs, activeRefreshIntervalMs);
                if (dirtyPlayers.contains(playerUuid)) {
                    if (!activeDue) {
                        activePlayers.add(playerUuid);
                        continue;
                    }
                    dirtyPlayers.remove(playerUuid);
                }
                if (!activeDue) {
                    activePlayers.add(playerUuid);
                    continue;
                }
                if (selectedSet.add(playerUuid)) {
                    selected.add(playerUuid);
                }
                activePlayers.add(playerUuid);
            }
        }

        private void selectRecovery(@Nonnull ArrayList<UUID> selected,
                                    @Nonnull HashSet<UUID> selectedSet,
                                    int limit,
                                    long nowMs) {
            int inspected = 0;
            while (selected.size() < limit
                    && inspected++ < MAX_QUEUE_INSPECTION
                    && !recoveryPlayers.isEmpty()) {
                UUID playerUuid = peekFirst(recoveryPlayers);
                if (playerUuid == null || !isRecoveryDue(playerUuid, nowMs)) {
                    break;
                }
                pollFirst(recoveryPlayers);
                recoveryDueMs.remove(playerUuid);
                if (!statesByPlayer.containsKey(playerUuid)) {
                    continue;
                }
                recoverySelected.add(playerUuid);
                if (selectedSet.add(playerUuid)) {
                    selected.add(playerUuid);
                }
            }
        }

        private boolean hasDueRecovery(long nowMs) {
            UUID playerUuid = peekFirst(recoveryPlayers);
            return playerUuid != null && isRecoveryDue(playerUuid, nowMs);
        }

        private boolean isRecoveryDue(@Nonnull UUID playerUuid, long nowMs) {
            Long dueMs = recoveryDueMs.get(playerUuid);
            return dueMs == null || nowMs >= dueMs;
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

        private void enqueueRecovery(@Nonnull UUID playerUuid, long dueMs) {
            recoveryPlayers.remove(playerUuid);
            recoveryDueMs.remove(playerUuid);
            recoverySelected.remove(playerUuid);
            recoveryPlayers.add(playerUuid);
            recoveryDueMs.put(playerUuid, dueMs);
        }

        private static long recoveryDueAt(long nowMs) {
            return nowMs > Long.MAX_VALUE - INACTIVE_RECOVERY_INTERVAL_MS
                    ? Long.MAX_VALUE
                    : nowMs + INACTIVE_RECOVERY_INTERVAL_MS;
        }

        @Nullable
        private static UUID peekFirst(@Nonnull LinkedHashSet<UUID> players) {
            Iterator<UUID> iterator = players.iterator();
            return iterator.hasNext() ? iterator.next() : null;
        }

        @Nullable
        private static UUID pollFirst(@Nonnull LinkedHashSet<UUID> players) {
            Iterator<UUID> iterator = players.iterator();
            if (!iterator.hasNext()) {
                return null;
            }
            UUID playerUuid = iterator.next();
            iterator.remove();
            return playerUuid;
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

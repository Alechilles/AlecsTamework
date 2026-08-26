package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns store-aware target HUD state and removes it when its player or store leaves the ECS. */
final class CommandTargetHudStateStore {
    private final Object storesLock = new Object();
    private final Map<Store<EntityStore>, StoreState> statesByStore = new IdentityHashMap<>();
    @Nullable
    private final CommandTargetHudPresentationCoordinator coordinator;

    CommandTargetHudStateStore(@Nonnull CommandTargetHudActivationTracker activationTracker) {
        this(activationTracker, null);
    }

    CommandTargetHudStateStore(
            @Nonnull CommandTargetHudActivationTracker activationTracker,
            @Nullable CommandTargetHudPresentationCoordinator coordinator
    ) {
        this.coordinator = coordinator;
        activationTracker.addLifecycleListener(new CommandTargetHudActivationTracker.LifecycleListener() {
            @Override
            public void onPlayerRemoved(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid) {
                closeCoordinatorPlayer(playerUuid);
                clearPlayerState(store, playerUuid);
            }

            @Override
            public void onStoreRemoved(@Nonnull Store<EntityStore> store) {
                clearStoreState(store);
            }
        });
    }

    @Nonnull
    StoreTickState tickState(@Nonnull Store<EntityStore> store) {
        return stateForStore(store).tickState();
    }

    @Nullable
    HudState stateForStore(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid) {
        return stateForStore(store).stateForPlayer(playerUuid);
    }

    void put(@Nonnull Store<EntityStore> store,
             @Nonnull UUID playerUuid,
             @Nonnull HudState state) {
        stateForStore(store).put(playerUuid, state);
    }

    void remove(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid) {
        StoreState state = existingStoreState(store);
        if (state != null) {
            state.remove(playerUuid);
        }
    }

    private void clearPlayerState(@Nonnull Store<EntityStore> store,
                                  @Nonnull UUID playerUuid) {
        StoreState state = existingStoreState(store);
        if (state != null) {
            state.clearPlayer(playerUuid);
        }
    }

    private void clearStoreState(@Nonnull Store<EntityStore> store) {
        StoreState state;
        synchronized (storesLock) {
            state = statesByStore.remove(store);
        }
        if (state != null) {
            state.clearStore(this::closeCoordinatorPlayer);
        }
    }

    private void closeCoordinatorPlayer(@Nonnull UUID playerUuid) {
        if (coordinator != null) {
            coordinator.closePlayer(playerUuid);
        }
    }

    @Nonnull
    private StoreState stateForStore(@Nonnull Store<EntityStore> store) {
        synchronized (storesLock) {
            return statesByStore.computeIfAbsent(store, ignored -> new StoreState());
        }
    }

    @Nullable
    private StoreState existingStoreState(@Nonnull Store<EntityStore> store) {
        synchronized (storesLock) {
            return statesByStore.get(store);
        }
    }

    private static void closePresentation(@Nonnull HudState state) {
        if (state.presentation() != null) {
            state.presentation().closeFromStateStore();
        }
    }

    private static final class StoreState {
        private final Map<UUID, HudState> stateByPlayer = new HashMap<>();
        private final StoreTickState tickState = new StoreTickState();

        @Nonnull
        private synchronized StoreTickState tickState() {
            return tickState;
        }

        @Nullable
        private synchronized HudState stateForPlayer(@Nonnull UUID playerUuid) {
            return stateByPlayer.get(playerUuid);
        }

        private synchronized void put(@Nonnull UUID playerUuid, @Nonnull HudState state) {
            stateByPlayer.put(playerUuid, state);
        }

        private synchronized void remove(@Nonnull UUID playerUuid) {
            stateByPlayer.remove(playerUuid);
        }

        private synchronized void clearPlayer(@Nonnull UUID playerUuid) {
            HudState previous = stateByPlayer.remove(playerUuid);
            if (previous != null) {
                closePresentation(previous);
            }
        }

        private synchronized void clearStore(@Nonnull java.util.function.Consumer<UUID> coordinatorCloser) {
            for (HudState state : stateByPlayer.values()) {
                if (state.presentation() != null) {
                    coordinatorCloser.accept(state.presentation().playerUuid());
                    closePresentation(state);
                }
            }
            stateByPlayer.clear();
        }
    }

    record HudState(@Nonnull Store<EntityStore> store,
                    @Nullable String targetKey,
                    long lastRefreshMs,
                    @Nullable CommandTargetHudPresentation presentation,
                    boolean visible,
                    long lastTargetScanMs,
                    @Nullable String activeItemId) {
    }

    /** Keeps scheduler deadlines separate for each entity store. */
    static final class StoreTickState {
        volatile long nextSweepAtMs;
    }
}

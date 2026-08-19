package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.TameworkCommandTargetHud;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns store-aware target HUD state and removes it when its player or store leaves the ECS. */
final class CommandTargetHudStateStore {
    private final Map<UUID, HudState> stateByPlayer = new HashMap<>();
    private final Map<Store<EntityStore>, StoreTickState> storeTickStateByStore = new IdentityHashMap<>();

    CommandTargetHudStateStore(@Nonnull CommandTargetHudActivationTracker activationTracker) {
        activationTracker.addLifecycleListener(new CommandTargetHudActivationTracker.LifecycleListener() {
            @Override
            public void onPlayerRemoved(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid) {
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
        return storeTickStateByStore.computeIfAbsent(store, ignored -> new StoreTickState());
    }

    @Nullable
    HudState stateForStore(@Nonnull Store<EntityStore> store, @Nonnull UUID playerUuid) {
        HudState previous = stateByPlayer.get(playerUuid);
        if (previous != null && previous.store() != store) {
            hide(previous);
            stateByPlayer.remove(playerUuid);
            return null;
        }
        return previous;
    }

    void put(@Nonnull UUID playerUuid, @Nonnull HudState state) {
        stateByPlayer.put(playerUuid, state);
    }

    void remove(@Nonnull UUID playerUuid) {
        stateByPlayer.remove(playerUuid);
    }

    private void clearPlayerState(@Nonnull Store<EntityStore> store,
                                  @Nonnull UUID playerUuid) {
        HudState previous = stateByPlayer.get(playerUuid);
        if (previous == null || previous.store() != store) {
            return;
        }
        hide(previous);
        stateByPlayer.remove(playerUuid);
    }

    private void clearStoreState(@Nonnull Store<EntityStore> store) {
        java.util.Iterator<Map.Entry<UUID, HudState>> iterator = stateByPlayer.entrySet().iterator();
        while (iterator.hasNext()) {
            HudState previous = iterator.next().getValue();
            if (previous.store() == store) {
                hide(previous);
                iterator.remove();
            }
        }
        storeTickStateByStore.remove(store);
    }

    private static void hide(@Nonnull HudState state) {
        if (state.hud() != null) {
            state.hud().hideNow();
        }
    }

    record HudState(@Nonnull Store<EntityStore> store,
                    @Nullable String targetKey,
                    long lastRefreshMs,
                    @Nullable TameworkCommandTargetHud hud,
                    boolean visible,
                    long lastTargetScanMs,
                    @Nullable String activeItemId) {
    }

    /** Keeps scheduler deadlines separate for each entity store. */
    static final class StoreTickState {
        long nextSweepAtMs;
        long nextFallbackDiscoveryAtMs;
    }
}

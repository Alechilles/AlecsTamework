package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/** Prevents command HUD packets before the client can render gameplay UI. */
final class CommandHudClientReadiness {
    private CommandHudClientReadiness() {
    }

    static boolean canRender(@Nullable Player player) {
        return player != null && !player.isWaitingForClientReady();
    }

    static boolean canRenderOrRetry(@Nullable Player player,
                                    CommandTargetHudActivationTracker tracker,
                                    @Nullable Store<EntityStore> store,
                                    UUID playerUuid) {
        if (canRender(player)) {
            return true;
        }
        if (player != null) {
            // Candidate selection consumes the join signal. Keep it in the existing
            // bounded world-thread sweep until ClientReady; departure clears the queue.
            tracker.markDirty(store, playerUuid);
        }
        return false;
    }
}

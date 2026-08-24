package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.entity.entities.Player;
import javax.annotation.Nullable;

/** Prevents command HUD packets before the client can render gameplay UI. */
final class CommandHudClientReadiness {
    private CommandHudClientReadiness() {
    }

    static boolean canRender(@Nullable Player player) {
        return player != null && !player.isWaitingForClientReady();
    }
}

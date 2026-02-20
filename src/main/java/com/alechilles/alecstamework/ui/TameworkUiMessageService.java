package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Displays Tamework HUD messages to a player.
 */
public final class TameworkUiMessageService {
    public boolean show(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return false;
        }
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        HudManager hudManager = player.getHudManager();
        if (hudManager == null) {
            return false;
        }
        setMessageHud(hudManager, playerRef, message);
        return true;
    }

    /**
     * Always installs a fresh HUD instance to avoid stale selector updates when the active custom HUD changes.
     */
    private void setMessageHud(HudManager hudManager,
                               PlayerRef playerRef,
                               String message) {
        TameworkMessageHud messageHud = new TameworkMessageHud(playerRef, message);
        hudManager.setCustomHud(playerRef, messageHud);
    }
}

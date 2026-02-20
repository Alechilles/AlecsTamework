package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Displays Tamework HUD messages to a player.
 */
public final class TameworkUiMessageService {
    private static final long UI_MESSAGE_DURATION_MS = 1200L;
    private static final ConcurrentHashMap<UUID, Integer> UI_MESSAGE_TOKENS = new ConcurrentHashMap<>();

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
        UUID playerId = player.getUuid();
        int token = UI_MESSAGE_TOKENS.merge(playerId, 1, Integer::sum);
        boolean shown = setMessageHud(player, hudManager, playerRef, message);
        if (!shown) {
            UI_MESSAGE_TOKENS.remove(playerId, token);
            return false;
        }
        scheduleClear(playerId, token, player, hudManager, playerRef, UI_MESSAGE_DURATION_MS);
        return shown;
    }

    /**
     * Always installs a fresh HUD instance to avoid stale selector updates when the active custom HUD changes.
     */
    private boolean setMessageHud(Player player,
                                  HudManager hudManager,
                                  PlayerRef playerRef,
                                  String message) {
        TameworkMessageHud messageHud = new TameworkMessageHud(playerRef, message);
        return TameworkHudCompat.setCustomHud(player, hudManager, playerRef, messageHud);
    }

    /**
     * Clears the message once its display window passes, but only if Tamework HUD is still active.
     */
    private void scheduleClear(UUID playerId,
                               int token,
                               Player player,
                               HudManager hudManager,
                               PlayerRef playerRef,
                               long delayMs) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (!isUiMessageTokenCurrent(playerId, token)) {
                return;
            }
            UI_MESSAGE_TOKENS.remove(playerId, token);
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }
            if (!TameworkHudCompat.isMultiHudAvailable() && !(hudManager.getCustomHud() instanceof TameworkMessageHud)) {
                return;
            }
            setMessageHud(player, hudManager, playerRef, "");
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private boolean isUiMessageTokenCurrent(UUID playerId, int token) {
        Integer current = UI_MESSAGE_TOKENS.get(playerId);
        return current != null && current == token;
    }
}

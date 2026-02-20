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
    private static final long UI_MESSAGE_REAPPLY_DELAY_MS = 80L;
    private static final long UI_MESSAGE_CLEAR_DELAY_MS = 1200L;
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
        setMessageHud(hudManager, playerRef, message);
        scheduleReapply(playerId, token, hudManager, playerRef, message);
        scheduleClear(playerId, token, hudManager, playerRef);
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

    /**
     * Re-applies the HUD after a short delay so page-close teardown does not swallow the message.
     */
    private void scheduleReapply(UUID playerId,
                                 int token,
                                 HudManager hudManager,
                                 PlayerRef playerRef,
                                 String message) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (!isUiMessageTokenCurrent(playerId, token)) {
                return;
            }
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }
            setMessageHud(hudManager, playerRef, message);
        }, UI_MESSAGE_REAPPLY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Clears the message once its display window passes, but only if Tamework HUD is still active.
     */
    private void scheduleClear(UUID playerId,
                               int token,
                               HudManager hudManager,
                               PlayerRef playerRef) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (!isUiMessageTokenCurrent(playerId, token)) {
                return;
            }
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }
            if (!(hudManager.getCustomHud() instanceof TameworkMessageHud)) {
                return;
            }
            setMessageHud(hudManager, playerRef, "");
            UI_MESSAGE_TOKENS.remove(playerId, token);
        }, UI_MESSAGE_CLEAR_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private boolean isUiMessageTokenCurrent(UUID playerId, int token) {
        Integer current = UI_MESSAGE_TOKENS.get(playerId);
        return current != null && current == token;
    }
}

package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ui.TameworkMessageHud;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Interaction ui message service. */
final class InteractionUiMessageService {
    private static final long UI_MESSAGE_DURATION_MS = 1200L;
    private static final long UI_MESSAGE_SHOW_DELAY_MS = 120L;
    private static final long UI_MESSAGE_SHOW_RETRY_DELAY_MS = 260L;
    private static final ConcurrentHashMap<UUID, Integer> UI_MESSAGE_TOKENS = new ConcurrentHashMap<>();

    boolean show(Player player, String message) {
        if (message == null || message.isBlank() || player == null) {
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
        scheduleShow(playerId, token, hudManager, playerRef, message, UI_MESSAGE_SHOW_DELAY_MS);
        scheduleShow(playerId, token, hudManager, playerRef, message, UI_MESSAGE_SHOW_RETRY_DELAY_MS);
        scheduleClear(playerId, token, hudManager, playerRef, UI_MESSAGE_SHOW_RETRY_DELAY_MS + UI_MESSAGE_DURATION_MS);
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

    private void scheduleShow(UUID playerId,
                              int token,
                              HudManager hudManager,
                              PlayerRef playerRef,
                              String message,
                              long delayMs) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (!isUiMessageTokenCurrent(playerId, token)) {
                return;
            }
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }
            setMessageHud(hudManager, playerRef, message);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Clears the message once its display window passes, but only if Tamework HUD is still active.
     */
    private void scheduleClear(UUID playerId,
                               int token,
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
            if (!(hudManager.getCustomHud() instanceof TameworkMessageHud)) {
                return;
            }
            setMessageHud(hudManager, playerRef, "");
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private boolean isUiMessageTokenCurrent(UUID playerId, int token) {
        Integer current = UI_MESSAGE_TOKENS.get(playerId);
        return current != null && current == token;
    }
}

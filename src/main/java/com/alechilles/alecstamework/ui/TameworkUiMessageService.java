package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Displays short-lived Tamework HUD messages to a player with fade-out steps.
 */
public final class TameworkUiMessageService {
    private static final long UI_MESSAGE_DURATION_MS = 1200L;
    private static final int UI_MESSAGE_FADE_STEP_COUNT = 6;
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
        TameworkMessageHud hud = resolveOrCreateMessageHud(hudManager, playerRef, message);
        scheduleUiMessageFadeSteps(playerId, token, hudManager, hud, playerRef);
        scheduleUiMessageHide(playerId, token, hudManager, hud, playerRef, UI_MESSAGE_DURATION_MS);
        return true;
    }

    private TameworkMessageHud resolveOrCreateMessageHud(HudManager hudManager,
                                                         PlayerRef playerRef,
                                                         String message) {
        CustomUIHud currentHud = hudManager.getCustomHud();
        if (currentHud instanceof TameworkMessageHud) {
            TameworkMessageHud messageHud = (TameworkMessageHud) currentHud;
            messageHud.updateMessage(message);
            return messageHud;
        }
        TameworkMessageHud messageHud = new TameworkMessageHud(playerRef, message);
        hudManager.setCustomHud(playerRef, messageHud);
        return messageHud;
    }

    private void scheduleUiMessageFadeSteps(UUID playerId,
                                            int token,
                                            HudManager hudManager,
                                            TameworkMessageHud hud,
                                            PlayerRef playerRef) {
        long intervalMs = UI_MESSAGE_DURATION_MS / UI_MESSAGE_FADE_STEP_COUNT;
        if (intervalMs <= 0L) {
            intervalMs = 1L;
        }
        for (int i = 1; i < UI_MESSAGE_FADE_STEP_COUNT; i++) {
            int step = i;
            long delayMs = i * intervalMs;
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                if (!isUiMessageTokenCurrent(playerId, token)) {
                    return;
                }
                if (!playerRef.isValid()) {
                    return;
                }
                if (hudManager.getCustomHud() != hud) {
                    return;
                }
                hud.showFadeStep(step);
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleUiMessageHide(UUID playerId,
                                       int token,
                                       HudManager hudManager,
                                       TameworkMessageHud hud,
                                       PlayerRef playerRef,
                                       long delayMs) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (!isUiMessageTokenCurrent(playerId, token)) {
                return;
            }
            if (!playerRef.isValid()) {
                return;
            }
            if (hudManager.getCustomHud() != hud) {
                return;
            }
            hud.hideMessage();
            UI_MESSAGE_TOKENS.remove(playerId, token);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private boolean isUiMessageTokenCurrent(UUID playerId, int token) {
        Integer current = UI_MESSAGE_TOKENS.get(playerId);
        return current != null && current == token;
    }
}

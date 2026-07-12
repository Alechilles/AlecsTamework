package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Formats and rate-limits owner-related chat messages.
 */
public final class OwnerMessageUtil {
    private static final long COOLDOWN_MS = 1000L;
    private static final ConcurrentHashMap<UUID, Long> LAST_SENT = new ConcurrentHashMap<>();

    private OwnerMessageUtil() {
    }

    public static void sendDenied(Player player,
                                  String npcName,
                                  String ownerName,
                                  UUID ownerUuid,
                                  String verb) {
        if (!canSend(player)) {
            return;
        }

        // Resolve a human-friendly NPC/owner label for messaging.
        String resolvedNpc = npcName != null && !npcName.isBlank() ? npcName : "pet";
        String resolvedOwner = ownerName != null && !ownerName.isBlank()
                ? ownerName
                : (ownerUuid != null ? ownerUuid.toString() : "someone");
        String resolvedVerb = verb != null && !verb.isBlank() ? verb : "interact with";

        send(player, Message.raw(
                "That " + resolvedNpc + " belongs to " + resolvedOwner
                        + ". You cannot " + resolvedVerb
                        + " a pet that does not belong to you."
        ));
    }

    public static void sendUntamed(Player player, String npcName) {
        sendUntamed(player, npcName, null);
    }

    public static void sendUntamed(Player player, String npcName, String foodList) {
        if (!canSend(player)) {
            return;
        }

        // Keep the base message short; optionally append allowed foods.
        String resolvedNpc = npcName != null && !npcName.isBlank() ? npcName : "pet";
        String message = "You must tame that " + resolvedNpc + " before capturing it.";
        if (foodList != null && !foodList.isBlank()) {
            message += " Try feeding: " + foodList + ".";
        }
        send(player, Message.raw(message));
    }

    public static void sendPopulationCapReached(Player player,
                                                int currentCount,
                                                int limit,
                                                TwGlobalConfig.PerPlayerLimitScope scope) {
        if (!canSend(player)) {
            return;
        }
        int safeCurrent = Math.max(0, currentCount);
        int safeLimit = Math.max(0, limit);
        TwGlobalConfig.PerPlayerLimitScope safeScope = scope == null
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : scope;
        String scopeLabel = safeScope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                ? "across loaded worlds"
                : "in this world";
        notifyWarning(
                player,
                "You cannot tame more NPCs right now (" + safeCurrent + "/" + safeLimit
                        + " owned " + scopeLabel + ")."
        );
    }

    public static void sendClaimPopulationCapReached(Player player, int currentCount, int limit) {
        if (!canSend(player)) {
            return;
        }
        int safeCurrent = Math.max(0, currentCount);
        int safeLimit = Math.max(0, limit);
        notifyWarning(
                player,
                "You cannot tame more NPCs in this claim right now ("
                        + safeCurrent + "/" + safeLimit + " owned in this claim)."
        );
    }

    public static void sendPopulationUnavailable(Player player, String reason) {
        if (!canSend(player)) {
            return;
        }
        String safeReason = reason == null || reason.isBlank() ? "policy unavailable" : reason.trim();
        notifyWarning(player, "Companion admission is unavailable: " + safeReason + ".");
    }

    private static boolean canSend(Player player) {
        if (player == null) {
            return false;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        // Rate-limit to avoid spamming chat during rapid interactions.
        Long last = LAST_SENT.get(playerUuid);
        if (last != null && now - last < COOLDOWN_MS) {
            return false;
        }
        LAST_SENT.put(playerUuid, now);
        return true;
    }

    private static void send(Player player, Message message) {
        PlayerRef playerRef = player != null ? player.getPlayerRef() : null;
        if (playerRef != null) {
            playerRef.sendMessage(message);
        }
    }

    private static void notifyWarning(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        new TameworkUiMessageService().show(player, message, NotificationStyle.Warning);
    }
}

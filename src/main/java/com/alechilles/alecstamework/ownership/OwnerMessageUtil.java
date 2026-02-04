package com.alechilles.alecstamework.ownership;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // Rate-limit to avoid spamming chat during rapid interactions.
        Long last = LAST_SENT.get(playerUuid);
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        LAST_SENT.put(playerUuid, now);

        // Resolve a human-friendly NPC/owner label for messaging.
        String resolvedNpc = npcName != null && !npcName.isBlank() ? npcName : "pet";
        String resolvedOwner = ownerName != null && !ownerName.isBlank()
                ? ownerName
                : (ownerUuid != null ? ownerUuid.toString() : "someone");
        String resolvedVerb = verb != null && !verb.isBlank() ? verb : "interact with";

        player.sendMessage(Message.raw(
                "That " + resolvedNpc + " belongs to " + resolvedOwner
                        + ". You cannot " + resolvedVerb
                        + " a pet that does not belong to you."
        ));
    }

    public static void sendUntamed(Player player, String npcName) {
        sendUntamed(player, npcName, null);
    }

    public static void sendUntamed(Player player, String npcName, String foodList) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_SENT.get(playerUuid);
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        LAST_SENT.put(playerUuid, now);

        // Keep the base message short; optionally append allowed foods.
        String resolvedNpc = npcName != null && !npcName.isBlank() ? npcName : "pet";
        String message = "You must tame that " + resolvedNpc + " before capturing it.";
        if (foodList != null && !foodList.isBlank()) {
            message += " Try feeding: " + foodList + ".";
        }
        player.sendMessage(Message.raw(message));
    }
}

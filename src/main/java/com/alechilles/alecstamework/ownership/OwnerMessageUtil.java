package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
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

    /**
     * Tells the player why {@link OwnerPopulationCapService} refused an acquisition, in the
     * player's language: the decision's own message key when it has one (a provider's key, a
     * domain limit, "checking requirements"), else the owned or deployed limit, a population-group limit, a
     * provider refusal, or that the limits cannot be checked right now.
     */
    public static void sendAcquisitionDenied(Player player, OwnerPopulationCapService.Decision decision) {
        if (decision == null || decision.allowed() || !canSend(player)) {
            return;
        }
        new TameworkUiMessageService().showKey(player, NotificationStyle.Warning, acquisitionDeniedKey(decision));
    }

    /** The translation key {@link #sendAcquisitionDenied} shows for a refused decision. */
    static String acquisitionDeniedKey(OwnerPopulationCapService.Decision decision) {
        if (decision.messageKey() != null && !decision.messageKey().isBlank()) {
            return decision.messageKey();
        }
        return switch (decision.reason()) {
            case "owner-cap-reached" -> "tamework.ui.population.ownedLimit";
            case OwnerPopulationCapService.REASON_DEPLOYED_CAP -> CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY;
            case OwnerPopulationCapService.REASON_GROUP_CAP -> "tamework.ui.population.groupLimit";
            case OwnerPopulationCapService.REASON_PROVIDER_DENIED -> CompanionAdmission.PROVIDER_DENIED_MESSAGE_KEY;
            case OwnerPopulationCapService.REASON_PROVIDER_UNAVAILABLE ->
                    CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY;
            default -> "tamework.ui.population.unavailable";
        };
    }

    /**
     * Tells the player the owned companion limit is reached, in the player's language. The count,
     * limit and scope are not shown; they stay in the signature for the existing callers.
     */
    public static void sendPopulationCapReached(Player player,
                                                int currentCount,
                                                int limit,
                                                TwGlobalConfig.PerPlayerLimitScope scope) {
        if (!canSend(player)) {
            return;
        }
        new TameworkUiMessageService().showKey(player, NotificationStyle.Warning, "tamework.ui.population.ownedLimit");
    }

    public static void sendClaimPopulationCapReached(Player player, int currentCount, int limit) {
        if (!canSend(player)) {
            return;
        }
        new TameworkUiMessageService().showKey(player, NotificationStyle.Warning,
                "tamework.ui.population.claimLimit", Math.max(0, currentCount), Math.max(0, limit));
    }

    /**
     * Tells the player the companion limits cannot be checked right now. {@code reason} is a
     * diagnostic identifier for logs and is not shown.
     */
    public static void sendPopulationUnavailable(Player player, String reason) {
        if (!canSend(player)) {
            return;
        }
        new TameworkUiMessageService().showKey(player, NotificationStyle.Warning, "tamework.ui.population.unavailable");
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
}

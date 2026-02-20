package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.util.NotificationUtil;

/**
 * Displays transient Tamework feedback using the built-in notification channel.
 */
public final class TameworkUiMessageService {
    public boolean show(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return false;
        }
        PacketHandler packetHandler = player.getPlayerConnection();
        if (packetHandler == null) {
            return false;
        }
        NotificationUtil.sendNotification(packetHandler, Message.raw(message), NotificationStyle.Default);
        return true;
    }
}

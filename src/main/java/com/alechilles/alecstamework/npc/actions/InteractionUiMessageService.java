package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;

/** Interaction ui message service. */
final class InteractionUiMessageService {
    private final TameworkUiMessageService delegate = new TameworkUiMessageService();

    boolean show(Player player, String message) {
        return delegate.show(player, message);
    }

    boolean showSuccessKey(Player player, String key, Object... args) {
        return delegate.showKey(player, NotificationStyle.Success, key, args);
    }

    boolean showWarningKey(Player player, String key, Object... args) {
        return delegate.showKey(player, NotificationStyle.Warning, key, args);
    }
}

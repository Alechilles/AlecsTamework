package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.server.core.entity.entities.Player;

/** Interaction ui message service. */
final class InteractionUiMessageService {
    private final TameworkUiMessageService delegate = new TameworkUiMessageService();

    boolean show(Player player, String message) {
        return delegate.show(player, message);
    }
}

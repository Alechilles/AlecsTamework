package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/** Delivers one already-built linked-panel update packet. */
@FunctionalInterface
interface LinkedNpcPanelPacketSender {
    void send(UICommandBuilder commands, UIEventBuilder events);
}

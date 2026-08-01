package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Defers replacement-page actions until the current UI packet has drained. */
@FunctionalInterface
interface LinkedNpcPanelDeferredNavigator {
    void navigate(PlayerRef player, Runnable action);
}

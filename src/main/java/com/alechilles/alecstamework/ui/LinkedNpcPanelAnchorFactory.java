package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

/**
 * Builds anchor values for linked NPC panel elements.
 */
final class LinkedNpcPanelAnchorFactory {
    private LinkedNpcPanelAnchorFactory() {
    }

    static Anchor buildHealthFillAnchor(double ratio, int maxWidth) {
        int width = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * maxWidth);
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(1));
        anchor.setTop(Value.of(1));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(12));
        return anchor;
    }
}

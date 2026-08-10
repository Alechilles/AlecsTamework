package com.alechilles.alecstamework.npc.actions;

import javax.annotation.Nonnull;

/** Delivers mode-change feedback through floating text with a direct UI fallback. */
final class InteractionModeMessageDelivery {

    private InteractionModeMessageDelivery() {
    }

    static boolean deliver(@Nonnull String message,
                           boolean showFloatingText,
                           boolean showUiMessage,
                           @Nonnull Channels channels) {
        boolean floatingTextShown = showFloatingText
                && channels.showFloatingText(message);
        boolean shouldShowUiMessage = showUiMessage
                || (showFloatingText && !floatingTextShown);
        boolean uiMessageShown = shouldShowUiMessage
                && channels.showUiMessage(message);
        return floatingTextShown || uiMessageShown;
    }

    interface Channels {
        boolean showFloatingText(@Nonnull String message);

        boolean showUiMessage(@Nonnull String message);
    }
}

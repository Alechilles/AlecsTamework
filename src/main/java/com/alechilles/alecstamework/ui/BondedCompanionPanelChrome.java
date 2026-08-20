package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import javax.annotation.Nonnull;

/** Applies the intentionally minimal panel chrome for bonded companion rosters. */
final class BondedCompanionPanelChrome {
    private BondedCompanionPanelChrome() {
    }

    /**
     * Bonded companions are always roster-linked, so generic proximity,
     * auto-link, and grouping controls would only expose inert preferences.
     */
    static void bind(@Nonnull UICommandBuilder commands, boolean bondedRoster) {
        if (!bondedRoster) {
            return;
        }
        commands.set("#TameworkLinkedPanelAutoLinkControls.Visible", false);
        commands.set("#TameworkLinkedPanelActiveHighlightControls.Visible", false);
        commands.set("#TameworkLinkedPanelModeDropdown.Visible", false);
        commands.set("#TameworkLinkedPanelSubtitleRow.Visible", false);
        commands.set("#TameworkLinkedPanelGroupAssignOverlay.Visible", false);
    }
}

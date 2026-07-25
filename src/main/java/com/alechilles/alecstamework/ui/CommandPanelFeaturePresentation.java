package com.alechilles.alecstamework.ui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Feature-specific presentation attached to a panel row without expanding the
 * legacy {@link LinkedNpcEntry} view model.
 */
public record CommandPanelFeaturePresentation(
        @Nonnull CommandRosterStatusPresentation roster,
        @Nullable CommandReviveCostPresentation revival
) {
    public CommandPanelFeaturePresentation {
        if (roster == null) {
            throw new IllegalArgumentException(
                    "Roster presentation is required."
            );
        }
    }

    public boolean managesPaidRevival() {
        return roster.paidRevivalState();
    }
}

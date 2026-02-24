package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage.LinkedNpcEntry;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Applies primary vitals (health and happiness) to linked companion card controls.
 */
final class LinkedNpcPanelVitalsBinder {
    private static final int VITAL_FILL_MAX_WIDTH = 204;
    private static final String HAPPINESS_FILL_COLOR = "#f2c97c";

    private LinkedNpcPanelVitalsBinder() {
    }

    static void bind(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        bindHealth(commandBuilder, entrySelector, entry);
        bindHappiness(commandBuilder, entrySelector, entry);
    }

    private static void bindHealth(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        String healthTextSelector = entrySelector + " #HealthText";
        String healthFillSelector = entrySelector + " #HealthFill";
        if (entry.hasHealth()) {
            commandBuilder.set(
                    healthTextSelector + ".Text",
                    "Health: " + entry.currentHealth() + "/" + entry.maxHealth()
            );
            commandBuilder.set(healthFillSelector + ".Visible", true);
            commandBuilder.setObject(
                    healthFillSelector + ".Anchor",
                    LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(entry.healthRatio(), VITAL_FILL_MAX_WIDTH)
            );
            return;
        }
        if (entry.dead()) {
            commandBuilder.set(healthTextSelector + ".Text", LinkedNpcPanelStatusTextService.resolveDeadHealthText(entry));
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        if (!entry.loaded()) {
            commandBuilder.set(healthTextSelector + ".Text", LinkedNpcPanelStatusTextService.resolveUnavailableHealthText(entry));
            commandBuilder.set(healthFillSelector + ".Visible", false);
            return;
        }
        commandBuilder.set(healthTextSelector + ".Text", "Health: unavailable");
        commandBuilder.set(healthFillSelector + ".Visible", false);
    }

    private static void bindHappiness(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        String happinessTextSelector = entrySelector + " #HappinessText";
        String happinessFillSelector = entrySelector + " #HappinessFill";
        commandBuilder.set(happinessFillSelector + ".Background", HAPPINESS_FILL_COLOR);
        if (entry.hasHappiness()) {
            commandBuilder.set(
                    happinessTextSelector + ".Text",
                    "Happiness: " + entry.currentHappiness() + "/" + entry.maxHappiness()
            );
            commandBuilder.set(happinessFillSelector + ".Visible", true);
            commandBuilder.setObject(
                    happinessFillSelector + ".Anchor",
                    LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(entry.happinessRatio(), VITAL_FILL_MAX_WIDTH)
            );
            return;
        }
        if (entry.dead()) {
            commandBuilder.set(happinessTextSelector + ".Text", LinkedNpcPanelStatusTextService.resolveDeadHappinessText(entry));
            commandBuilder.set(happinessFillSelector + ".Visible", false);
            return;
        }
        if (!entry.loaded()) {
            commandBuilder.set(
                    happinessTextSelector + ".Text",
                    LinkedNpcPanelStatusTextService.resolveUnavailableHappinessText(entry)
            );
            commandBuilder.set(happinessFillSelector + ".Visible", false);
            return;
        }
        commandBuilder.set(happinessTextSelector + ".Text", "Happiness: unavailable");
        commandBuilder.set(happinessFillSelector + ".Visible", false);
    }
}

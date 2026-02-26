package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Applies primary vitals (health and happiness) to linked companion card controls.
 */
final class LinkedNpcPanelVitalsBinder {
    private static final int VITAL_FILL_MAX_WIDTH = 204;
    private static final String HAPPINESS_FILL_COLOR = "#f2c97c";
    private static final String HUNGER_FILL_COLOR = "#d9a066";
    private static final String THIRST_FILL_COLOR = "#76b7ea";

    private LinkedNpcPanelVitalsBinder() {
    }

    static void bind(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        bindHealth(commandBuilder, entrySelector, entry);
        bindHappiness(commandBuilder, entrySelector, entry);
        bindHunger(commandBuilder, entrySelector, entry);
        bindThirst(commandBuilder, entrySelector, entry);
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

    private static void bindHunger(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        String hungerTextSelector = entrySelector + " #HungerText";
        String hungerFillSelector = entrySelector + " #HungerFill";
        commandBuilder.set(hungerFillSelector + ".Background", HUNGER_FILL_COLOR);
        if (entry.hasHunger()) {
            commandBuilder.set(
                    hungerTextSelector + ".Text",
                    "Hunger: " + entry.currentHunger() + "/" + entry.maxHunger()
            );
            commandBuilder.set(hungerFillSelector + ".Visible", true);
            commandBuilder.setObject(
                    hungerFillSelector + ".Anchor",
                    LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(entry.hungerRatio(), VITAL_FILL_MAX_WIDTH)
            );
            return;
        }
        if (entry.dead()) {
            commandBuilder.set(hungerTextSelector + ".Text", "Hunger: unavailable (dead)");
            commandBuilder.set(hungerFillSelector + ".Visible", false);
            return;
        }
        if (!entry.loaded()) {
            commandBuilder.set(hungerTextSelector + ".Text", "Hunger: unavailable (unloaded)");
            commandBuilder.set(hungerFillSelector + ".Visible", false);
            return;
        }
        commandBuilder.set(hungerTextSelector + ".Text", "Hunger: unavailable");
        commandBuilder.set(hungerFillSelector + ".Visible", false);
    }

    private static void bindThirst(UICommandBuilder commandBuilder, String entrySelector, LinkedNpcEntry entry) {
        String thirstTextSelector = entrySelector + " #ThirstText";
        String thirstFillSelector = entrySelector + " #ThirstFill";
        commandBuilder.set(thirstFillSelector + ".Background", THIRST_FILL_COLOR);
        if (entry.hasThirst()) {
            commandBuilder.set(
                    thirstTextSelector + ".Text",
                    "Thirst: " + entry.currentThirst() + "/" + entry.maxThirst()
            );
            commandBuilder.set(thirstFillSelector + ".Visible", true);
            commandBuilder.setObject(
                    thirstFillSelector + ".Anchor",
                    LinkedNpcPanelAnchorFactory.buildHealthFillAnchor(entry.thirstRatio(), VITAL_FILL_MAX_WIDTH)
            );
            return;
        }
        if (entry.dead()) {
            commandBuilder.set(thirstTextSelector + ".Text", "Thirst: unavailable (dead)");
            commandBuilder.set(thirstFillSelector + ".Visible", false);
            return;
        }
        if (!entry.loaded()) {
            commandBuilder.set(thirstTextSelector + ".Text", "Thirst: unavailable (unloaded)");
            commandBuilder.set(thirstFillSelector + ".Visible", false);
            return;
        }
        commandBuilder.set(thirstTextSelector + ".Text", "Thirst: unavailable");
        commandBuilder.set(thirstFillSelector + ".Visible", false);
    }
}

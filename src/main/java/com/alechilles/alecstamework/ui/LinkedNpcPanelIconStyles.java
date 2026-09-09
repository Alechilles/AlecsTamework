package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Uses the same authored button styles for generic and bonded card states. */
final class LinkedNpcPanelIconStyles {
    private LinkedNpcPanelIconStyles() { }

    static void apply(UICommandBuilder commands, String card, LinkedNpcEntry entry) {
        style(commands, card + " #FlightToggleButton", entry.flightToggleAirborne()
                ? "FlightAirborne" : "FlightGrounded");
        style(commands, card + " #ShoulderRideButton", entry.shoulderRideMounted()
                ? "ShoulderOn" : "ShoulderOff");
        style(commands, card + " #RespawnButton", entry.lost() ? "Recover" : "Revive");
        commands.set(card + " #FlightModeGroundedIcon.Visible", false);
        commands.set(card + " #FlightModeAirborneIcon.Visible", false);
        commands.set(card + " #ShoulderRideIcon.Visible", false);
    }

    static void style(UICommandBuilder commands, String selector, String name) {
        commands.set(selector + ".Style", Value.ref("TameworkPanelActionStyles.ui", name));
    }
}

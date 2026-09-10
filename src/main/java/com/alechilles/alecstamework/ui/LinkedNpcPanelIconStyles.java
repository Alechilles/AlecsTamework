package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.Anchor;
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
        commands.set(selector + "Glyph.Background", "Tamework/PanelActions/" + name + "_Glyph_Default.png");
    }

    static void visible(UICommandBuilder commands, String selector, boolean visible) {
        commands.set(selector + ".Visible", visible);
        commands.set(selector + "Glyph.Visible", visible);
        if (!selector.contains("#Bonded") && !selector.endsWith("#RemoveButton")) {
            commands.set(selector + "Caption.Visible", visible);
        }
    }

    static void anchor(UICommandBuilder commands, String selector, Anchor anchor) {
        commands.setObject(selector + ".Anchor", anchor);
        commands.setObject(selector + "Glyph.Anchor", anchor);
    }

    static void placeAction(UICommandBuilder commands, String selector, int left) {
        Anchor position = new Anchor();
        position.setTop(Value.of(30));
        position.setLeft(Value.of(left));
        position.setWidth(Value.of(48));
        position.setHeight(Value.of(48));
        anchor(commands, selector, position);
        Anchor caption = new Anchor();
        caption.setTop(Value.of(80));
        caption.setLeft(Value.of(left - 6));
        caption.setWidth(Value.of(60));
        caption.setHeight(Value.of(16));
        commands.setObject(selector + "Caption.Anchor", caption);
    }
}

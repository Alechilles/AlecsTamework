package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/** Binds the compact command-wheel buttons independently from roster-card rendering. */
final class CommandPageButtonBinder {
    private CommandPageButtonBinder() { }
    static void bind(UICommandBuilder commands, UIEventBuilder events,
                     CommandSelectionOptionSource.Option[] options) {
        for (int i = 0; i < 8; i++) {
            String selector = "#CommandButton" + i, label = "#CommandLabel" + i;
            if (i >= options.length) { commands.set(selector + ".Visible", false); commands.set(label + ".Visible", false); continue; }
            CommandSelectionOptionSource.Option option = options[i];
            commands.set(selector + ".Visible", true); commands.set(selector + ".Text", "");
            commands.set(label + ".Visible", true); commands.set(label + ".Text", option.label());
            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID, option.id()), false);
        }
    }
}

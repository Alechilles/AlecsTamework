package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.List;

/** Renders sidebar shortcuts using the existing command-item group activation values. */
final class CommandGroupQuickSelectBinder {
    private static final String LIST = "#TameworkGroupQuickSelectList";

    private CommandGroupQuickSelectBinder() { }

    static void bind(UICommandBuilder commands, UIEventBuilder events,
                     LinkedNpcPanelRefreshValues values, List<DropdownEntryInfo> entries,
                     String selection, boolean available, boolean initial) {
        if (initial) {
            commands.set("#TameworkGroupQuickSelect.Visible", available);
            values.remember("#TameworkGroupQuickSelect.Visible", available);
        } else values.set(commands, "#TameworkGroupQuickSelect.Visible", available);
        if (!available) return;
        boolean entriesChanged = values.changed("groupQuickSelectEntries", entries);
        boolean rebuild = initial || entriesChanged;
        if (rebuild) commands.clear(LIST);
        for (int i = 0; i < entries.size(); i++) {
            DropdownEntryInfo entry = entries.get(i);
            String button = LIST + "[" + i + "] #QuickGroupButton";
            String style = entry.value().equals(selection) ? "PanelButtonSelected" : "PanelButton";
            if (rebuild) {
                commands.append(LIST, "TameworkCommandGroupQuickSelectRow.ui");
                commands.setObject(button + ".Text", entry.label());
                commands.set(button + ".Style", Value.ref("TameworkPanelActionStyles.ui", style));
                values.remember(button + ".Style", style);
                events.addEventBinding(CustomUIEventBindingType.Activating, button,
                        EventData.of(CommandSelectionPageEventBinder.KEY_PANEL_GROUP_ACTIVE_LITERAL,
                                entry.value()), false);
            } else values.setStyle(commands, button + ".Style", style);
        }
    }
}

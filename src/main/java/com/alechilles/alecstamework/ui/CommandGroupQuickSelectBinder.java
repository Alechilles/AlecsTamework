package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders sidebar shortcuts using the existing command-item group activation values. */
final class CommandGroupQuickSelectBinder {
    private static final String LIST = "#TameworkGroupQuickSelectList";

    private CommandGroupQuickSelectBinder() { }

    static void bind(UICommandBuilder commands, UIEventBuilder events,
                     LinkedNpcPanelRefreshValues values, List<DropdownEntryInfo> entries,
                     String selection, boolean available, boolean initial) {
        bind(commands, events, values, entries, selection, available, initial, Map.of());
    }

    static void bind(UICommandBuilder commands, UIEventBuilder events,
                     LinkedNpcPanelRefreshValues values, List<DropdownEntryInfo> entries,
                     String selection, boolean available, boolean initial,
                     Map<String, String> colors) {
        if (initial) {
            commands.set("#TameworkGroupQuickSelect.Visible", available);
            values.remember("#TameworkGroupQuickSelect.Visible", available);
        } else values.set(commands, "#TameworkGroupQuickSelect.Visible", available);
        if (!available) return;
        entries = entries.stream().filter(entry -> !"__none__".equals(entry.value())).toList();
        boolean entriesChanged = values.changed("groupQuickSelectEntries", entries);
        Map<String, String> groupColors = groupColors(colors);
        boolean rebuild = initial || entriesChanged;
        if (rebuild) commands.clear(LIST);
        for (int i = 0; i < entries.size(); i++) {
            DropdownEntryInfo entry = entries.get(i);
            String button = LIST + "[" + i + "] #QuickGroupButton";
            String swatch = LIST + "[" + i + "] #QuickGroupColor";
            String color = colorFor(entry, groupColors);
            String style = entry.value().equals(selection) ? "GroupRowSelected" : "GroupRow";
            if (rebuild) {
                commands.append(LIST, "TameworkCommandGroupQuickSelectRow.ui");
                commands.setObject(button + ".Text", entry.label());
                commands.setObject(button + ".TooltipText", entry.label());
                commands.set(button + ".Style", Value.ref("TameworkPanelActionStyles.ui", style));
                commands.setObject(swatch + ".Background", LinkedNpcPanelGroupTabBinder.markerStyle(color, !groupColors.containsKey(normalize(entry.value()))));
                commands.set(swatch + ".Visible", !"__all__".equalsIgnoreCase(entry.value()));
                values.remember(button + ".Style", style);
                values.remember(swatch + ".Background", color);
                events.addEventBinding(CustomUIEventBindingType.Activating, button,
                        EventData.of(CommandSelectionPageEventBinder.KEY_PANEL_GROUP_ACTIVE_LITERAL,
                                entry.value()), false);
            } else {
                values.setStyle(commands, button + ".Style", style);
                if (values.changed(swatch + ".Background", color)) {
                    commands.setObject(swatch + ".Background", LinkedNpcPanelGroupTabBinder.markerStyle(color, !groupColors.containsKey(normalize(entry.value()))));
                }
            }
        }
    }

    private static Map<String, String> groupColors(Map<String, String> source) {
        Map<String, String> colors = new HashMap<>();
        source.forEach((id, rawColor) -> {
            String color = normalizeColor(rawColor);
            if (color != null) colors.put(normalize(id), color);
        });
        return colors;
    }

    private static String colorFor(DropdownEntryInfo entry, Map<String, String> groupColors) {
        if (entry == null || entry.value() == null) return "#55635a";
        String color = groupColors.get(normalize(entry.value()));
        if (color != null) return color;
        // All and No Group stay neutral; selection is communicated by the button style.
        return "#55635a";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeColor(String value) {
        if (value == null || !value.trim().matches("^#[0-9A-Fa-f]{6}$")) return null;
        return "#" + value.trim().substring(1).toUpperCase(Locale.ROOT);
    }
}

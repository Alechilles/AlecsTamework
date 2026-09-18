package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.*;
import java.util.*;

/** Status/search are view-only; they never change the held flute's command selection. */
final class CompanionPanelChrome {
    static final String FILTER_PREFIX = "__companion_filter__:";
    static final List<String> FILTERS = List.of("InWorld", "Stored", "LostDead", "All");

    static void bind(UICommandBuilder c, UIEventBuilder e, TameworkCommandSelectionPage page,
                     LinkedNpcPanelRefreshValues values) {
        if (page.companionBinding == null) return;
        String language = page.resolveLanguage();
        if (values == null) {
            c.setObject("#TameworkLinkedPanelRoot #Title.Anchor", anchor(10, 10, 908, 66));
            c.set("#TameworkLinkedPanelModeTabs.Visible", false);
            c.set("#TameworkLinkedPanelAutoLinkControls.Visible", false);
            c.set("#TameworkLinkedPanelFilterLabel.Visible", false);
            c.set("#TameworkLinkedPanelFilterDropdown.Visible", false);
            c.set("#CompanionModeTabs.Visible", true);
            c.set("#CompanionNearbyControls.Visible", true);
            c.set("#CompanionGroupActions.Visible", true);
            c.set("#TameworkLinkedPanelFilterInput.PlaceholderText", text(language, "search"));
            c.set("#TameworkLinkedPanelFilterInput.MaxLength", 120);
            c.set("#TameworkLinkedPanelActiveHighlightLabel.Text", text(language, "highlight"));
            c.setObject("#TameworkLinkedPanelActiveHighlightControls.Anchor", anchor(0, 36, 146, 28));
            c.setObject("#CompanionNearbyControls.Anchor", anchor(155, 36, 115, 28));
            c.setObject("#TameworkLinkedPanelControlsSecondary.Anchor", anchor(280, 36, 610, 28));
            c.setObject("#TameworkLinkedPanelInlineFilterTextControls.Anchor", anchor(0, 0, 440, 28));
            c.setObject("#TameworkLinkedPanelFilterInput.Anchor", anchor(0, 0, 430, 28));
            c.setObject("#TameworkLinkedPanelListViewport.Anchor", anchor(10, 80, 906, 658));
            c.set("#TameworkLinkedPanelSubtitleRow.Visible", false);
            c.set("#TameworkLinkedPanelSubtitleRadiusSlot.Visible", false);
            e.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CompanionNearbyCheck",
                    EventData.of("@CompanionNearby", "#CompanionNearbyCheck.Value"), false);
            e.addEventBinding(CustomUIEventBindingType.Activating, "#CompanionClearSelection",
                    EventData.of(CommandSelectionPageEventBinder.KEY_PANEL_GROUP_ACTIVE_LITERAL, "__none__"), false);
            e.addEventBinding(CustomUIEventBindingType.Activating, "#CompanionAddGroup",
                    EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID,
                            CommandSelectionPageEventBinder.PANEL_MANAGE_GROUPS_COMMAND_ID), false);
        }
        set(c, values, "#TameworkLinkedPanelInlineFilterTextControls.Visible", true);
        set(c, values, "#CompanionNearbyCheck.Value", page.companionBinding.nearby().get());
        long count = Arrays.stream(page.baseLinkedNpcEntries).filter(LinkedNpcEntry::active).count();
        set(c, values, "#TameworkCommandMenuTitle.Text", text(language, "title") + " — "
                + LocalizedText.format(language, "tamework.ui.companions.count", count, page.baseLinkedNpcEntries.length));
        for (String filter : FILTERS) {
            String selector = "#Companion" + filter;
            String style = filter.equals(page.companionBinding.state().get()) ? "PanelButtonSelected" : "PanelButton";
            if (values == null) {
                c.set(selector + ".Style", Value.ref("TameworkPanelActionStyles.ui", style));
                e.addEventBinding(CustomUIEventBindingType.Activating, selector,
                        EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID, FILTER_PREFIX + filter), false);
            } else values.setStyle(c, selector + ".Style", style);
        }
    }

    static LinkedNpcEntry[] filter(LinkedNpcEntry[] entries, String state, boolean nearby, String search) {
        String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(entries).filter(entry -> matchesState(entry, state))
                .filter(entry -> !nearby || entry.nearby())
                .filter(entry -> needle.isEmpty() || contains(entry.displayName(), needle)
                        || contains(entry.speciesId(), needle) || contains(entry.speciesLabel(), needle)
                        || contains(entry.groupName(), needle)).toArray(LinkedNpcEntry[]::new);
    }

    static boolean matchesState(LinkedNpcEntry entry, String state) {
        boolean missing = entry.dead() || entry.lost();
        boolean stored = entry.captured() || entry.inCoop();
        return switch (state) {
            case "Stored" -> stored && !missing;
            case "LostDead" -> missing;
            case "InWorld" -> !stored && !missing;
            default -> true;
        };
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
    private static String text(String language, String key) { return LocalizedText.resolve(language, "tamework.ui.companions." + key); }
    private static void set(UICommandBuilder c, LinkedNpcPanelRefreshValues v, String selector, Object value) {
        if (v != null && !v.changed(selector, value)) return;
        if (value instanceof String s) c.set(selector, s); else if (value instanceof Boolean b) c.set(selector, b);
    }
    private static Anchor anchor(int left, int top, int width, int height) {
        var a = new Anchor(); a.setLeft(Value.of(left)); a.setTop(Value.of(top));
        a.setWidth(Value.of(width)); a.setHeight(Value.of(height)); return a;
    }
}

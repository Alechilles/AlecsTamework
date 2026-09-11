package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.TreeMap;
import javax.annotation.Nonnull;

/** Applies the intentionally minimal panel chrome for bonded companion rosters. */
final class BondedCompanionPanelChrome {
    static final String FILTER_COMMAND_PREFIX = "__bonded_roster_filter__";
    static final List<String> FILTERS = List.of("All", "Active", "Stored", "Dead");

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
        commands.set("#TameworkLinkedPanelModeTabs.Visible", false);
        commands.set("#TameworkLinkedPanelSubtitleRow.Visible", false);
        commands.set("#TameworkLinkedPanelSubtitleRadiusSlot.Visible", false);
        commands.set("#TameworkLinkedPanelGroupAssignOverlay.Visible", false);
    }
    static void bindToolbar(UICommandBuilder commands, UIEventBuilder events,
                            TameworkCommandSelectionPage page, LinkedNpcPanelRefreshValues values) {
        if (!page.config.usesBondedCompanionRoster()) return;
        String language = page.resolveLanguage();
        if (values == null) {
            bind(commands, true);
            commands.set("#TameworkGroupQuickSelect.Visible", false);
            commands.set("#BondedRosterModeTabs.Visible", true);
            commands.setObject("#TameworkLinkedPanelControlsSecondary.Anchor", anchor(300, 0, 408, 28));
            commands.set("#TameworkLinkedPanelFilterLabel.Visible", false);
            commands.set("#TameworkLinkedPanelFilterDropdown.Visible", false);
            commands.setObject("#TameworkLinkedPanelInlineFilterTextControls.Anchor", anchor(0, 0, 260, 28));
            commands.setObject("#TameworkLinkedPanelFilterInput.Anchor", anchor(0, 0, 260, 28));
            commands.set("#TameworkLinkedPanelFilterInput.PlaceholderText",
                    LocalizedText.resolve(language, "tamework.ui.roster.search"));
            commands.set("#TameworkLinkedPanelFilterInput.MaxLength", 120);
        }
        set(commands, values, "#TameworkLinkedPanelActiveHighlightControls.Visible", false);
        set(commands, values, "#TameworkLinkedPanelInlineFilterTextControls.Visible", true);
        set(commands, values, "#TameworkCommandMenuTitle.Text", LocalizedText.format(language,
                "tamework.ui.roster.title", page.baseLinkedNpcEntries.length));
        set(commands, values, "#TameworkLinkedPanelSortDropdown.Entries",
                CommandSelectionPanelOptions.resolveSortDropdownEntries(language).subList(0, 3));
        for (String filter : FILTERS) {
            String selector = "#BondedRoster" + filter;
            String style = filter.equals(page.rosterStateFilter) ? "PanelButtonSelected" : "PanelButton";
            if (values == null) commands.set(selector + ".Style", Value.ref("TameworkPanelActionStyles.ui", style));
            else values.setStyle(commands, selector + ".Style", style);
            if (values == null) events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID,
                            FILTER_COMMAND_PREFIX + filter), false);
        }
        String capacity = capacityText(page.featureController.presentations(), language);
        set(commands, values, "#BondedRosterCapacity.Visible", !capacity.isBlank());
        set(commands, values, "#BondedRosterCapacity.Text", capacity.contains("\n")
                ? LocalizedText.resolve(language, "tamework.ui.roster.capacity") : capacity);
        set(commands, values, "#BondedRosterCapacity.TooltipText", capacity);
        if (page.linkedNpcEntries.length == 0 && page.baseLinkedNpcEntries.length > 0) {
            set(commands, values, "#TameworkLinkedPanelEmptyState.Text",
                    LocalizedText.resolve(language, "tamework.ui.roster.emptyFilter"));
        }
    }

    static LinkedNpcEntry[] filter(LinkedNpcEntry[] entries,
                                   Map<UUID, CommandPanelFeaturePresentation> features, String selected) {
        if (!FILTERS.contains(selected) || "All".equals(selected)) return entries;
        return Arrays.stream(entries).filter(entry -> {
            CommandPanelFeaturePresentation feature = features.get(entry.npcUuid());
            return feature != null && feature.bonded() != null
                    && feature.bonded().status().state().name().equalsIgnoreCase(selected);
        }).toArray(LinkedNpcEntry[]::new);
    }

    static String capacityText(Map<UUID, CommandPanelFeaturePresentation> features, String language) {
        // Capacity belongs to a policy family, not to the currently filtered rows.
        Map<String, String> capacities = new TreeMap<>();
        for (CommandPanelFeaturePresentation feature : features.values()) {
            if (feature.bonded() == null) continue;
            Map<String, String> attributes = feature.bonded().attributes();
            String count = attributes.get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT);
            String limit = attributes.get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT);
            String label = attributes.getOrDefault(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LABEL, "");
            if (count == null || limit == null) continue;
            capacities.put(label, LocalizedText.format(language, "tamework.ui.roster.activeCapacity", count,
                    "0".equals(limit) ? "∞" : limit));
        }
        if (capacities.size() == 1) return capacities.values().iterator().next();
        return capacities.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static void set(UICommandBuilder commands, LinkedNpcPanelRefreshValues values,
                            String selector, Object value) {
        if (values != null && !values.changed(selector, value)) return;
        if (value instanceof String text) commands.set(selector, text);
        else if (value instanceof Boolean flag) commands.set(selector, flag);
        else if (value instanceof List<?> list) commands.set(selector, list);
        else commands.setObject(selector, value);
    }

    private static Anchor anchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(left));
        anchor.setTop(Value.of(top));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        return anchor;
    }
}

package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionNames;
import com.alechilles.alecstamework.companion.bonded.BondedRecords;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies the intentionally minimal panel chrome for bonded companion rosters. */
final class BondedCompanionPanelChrome {
    static final String FILTER_COMMAND_PREFIX = "__bonded_roster_filter__";
    static final List<String> FILTERS = List.of("All", "Active", "Stored", "Dead");
    /**
     * The filter tabs render in capitals with letter spacing and carry a count, so they are wider
     * than the markup's buttons: left and width of each, in {@link #FILTERS} order, 4 px apart.
     */
    private static final int[][] TAB_BOUNDS = {{0, 84}, {88, 108}, {200, 126}, {330, 96}};
    private static final int TABS_WIDTH = 426;
    /** Sort and search sit between the tabs and the capacity text inside the 908 px toolbar. */
    private static final int CONTROLS_LEFT = TABS_WIDTH + 6;
    private static final int SEARCH_WIDTH = 130;
    private static final int CONTROLS_WIDTH = 134 + SEARCH_WIDTH;
    private static final int CAPACITY_WIDTH = 908 - CONTROLS_LEFT - CONTROLS_WIDTH - 4;

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
            commands.setObject("#BondedRosterModeTabs.Anchor", anchor(0, 0, TABS_WIDTH, 30));
            for (int index = 0; index < TAB_BOUNDS.length; index++) {
                commands.setObject("#BondedRoster" + FILTERS.get(index) + ".Anchor",
                        anchor(TAB_BOUNDS[index][0], 0, TAB_BOUNDS[index][1], 30));
            }
            commands.setObject("#TameworkLinkedPanelControlsSecondary.Anchor",
                    anchor(CONTROLS_LEFT, 0, CONTROLS_WIDTH, 28));
            commands.set("#TameworkLinkedPanelFilterLabel.Visible", false);
            commands.set("#TameworkLinkedPanelFilterDropdown.Visible", false);
            commands.setObject("#TameworkLinkedPanelInlineFilterTextControls.Anchor",
                    anchor(0, 0, SEARCH_WIDTH, 28));
            commands.setObject("#TameworkLinkedPanelFilterInput.Anchor", anchor(0, 0, SEARCH_WIDTH, 28));
            Anchor capacityAnchor = new Anchor();
            capacityAnchor.setTop(Value.of(0));
            capacityAnchor.setRight(Value.of(0));
            capacityAnchor.setWidth(Value.of(CAPACITY_WIDTH));
            capacityAnchor.setHeight(Value.of(28));
            commands.setObject("#BondedRosterCapacity.Anchor", capacityAnchor);
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
        Map<UUID, CommandPanelFeaturePresentation> features = page.featureController.presentations();
        for (String filter : FILTERS) {
            String selector = "#BondedRoster" + filter;
            String style = filter.equals(page.rosterStateFilter)
                    ? "CompanionTabButtonSelected" : "CompanionTabButton";
            // Each tab counts the whole roster in its state, whatever the search box holds.
            set(commands, values, selector + ".Text", LocalizedText.format(language,
                    "tamework.ui.companions.tabCount",
                    LocalizedText.resolve(language, "tamework.ui.roster.filter."
                            + filter.toLowerCase(Locale.ROOT)),
                    filter(page.baseLinkedNpcEntries, features, filter).length));
            if (values == null) commands.set(selector + ".Style", Value.ref("TameworkSlateStyles.ui", style));
            else values.setStyle(commands, selector + ".Style", style);
            if (values == null) events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    EventData.of(CommandSelectionPageEventBinder.EVENT_COMMAND_ID,
                            FILTER_COMMAND_PREFIX + filter), false);
        }
        String capacity = capacityHeader(features, language);
        set(commands, values, "#BondedRosterCapacity.Visible", !capacity.isBlank());
        set(commands, values, "#BondedRosterCapacity.Text", capacity);
        set(commands, values, "#BondedRosterCapacity.TooltipText", capacityText(features, language));
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

    /**
     * The header capacity text. One family shows its active and owned counts. Several families do
     * not fit on the line with both, so each shows its active count (its owned count when no
     * family has an active limit) and the tooltip, {@link #capacityText}, carries the rest.
     * Families are named only when every one of them has a translated name: its roster config's
     * {@code NameKey}, else the name of its role when it allows a single role.
     */
    static String capacityHeader(Map<UUID, CommandPanelFeaturePresentation> features, String language) {
        List<Family> families = families(features, language);
        if (families.size() <= 1) return capacityText(features, language);
        boolean active = families.stream().anyMatch(Family::active);
        List<Family> shown = families.stream().filter(family -> active ? family.active() : family.owned())
                .toList();
        if (shown.stream().allMatch(family -> family.name() != null)) {
            return shown.stream().map(family -> LocalizedText.format(language,
                    "tamework.ui.roster.familyCapacity", family.name(),
                    active ? family.count() : family.ownedCount(),
                    active ? family.limit() : family.ownedLimit())).collect(Collectors.joining(" \u00b7 "));
        }
        // No names: the label once, then each family's numbers in the tooltip's order.
        StringBuilder text = new StringBuilder();
        for (Family family : shown) {
            String count = active ? family.count() : family.ownedCount();
            String limit = active ? family.limit() : family.ownedLimit();
            text.append(text.isEmpty()
                    ? LocalizedText.format(language, active ? "tamework.ui.roster.activeCapacity"
                            : "tamework.ui.roster.ownedCapacity", count, limit)
                    : ", " + LocalizedText.format(language, "tamework.ui.roster.capacityCount", count, limit));
        }
        return text.toString();
    }

    /**
     * Every family's active and owned counts against their limits, one line per family. A part
     * whose limit is unlimited carries no count and is left out. With several families, a family
     * that has a translated name is labelled with it.
     */
    static String capacityText(Map<UUID, CommandPanelFeaturePresentation> features, String language) {
        List<Family> families = families(features, language);
        return families.stream().map(family -> {
            String counts = family.active() && family.owned()
                    ? LocalizedText.format(language, "tamework.ui.roster.activeOwnedCapacity",
                            family.count(), family.limit(), family.ownedCount(), family.ownedLimit())
                    : family.active()
                    ? LocalizedText.format(language, "tamework.ui.roster.activeCapacity",
                            family.count(), family.limit())
                    : LocalizedText.format(language, "tamework.ui.roster.ownedCapacity",
                            family.ownedCount(), family.ownedLimit());
            return families.size() == 1 || family.name() == null ? counts
                    : LocalizedText.format(language, "tamework.ui.roster.familyLine", family.name(), counts);
        }).collect(Collectors.joining("\n"));
    }

    /** One policy family's counts; a null pair means that limit is not set. */
    private record Family(@Nullable String name, @Nullable String count, @Nullable String limit,
                          @Nullable String ownedCount, @Nullable String ownedLimit) {
        boolean active() {
            return count != null && limit != null;
        }

        boolean owned() {
            return ownedCount != null && ownedLimit != null;
        }
    }

    /** The families with at least one limit, in a stable order. */
    private static List<Family> families(Map<UUID, CommandPanelFeaturePresentation> features, String language) {
        // Capacity belongs to a policy family, not to the currently filtered rows. The family id
        // (the capacity label on older sources) only tells the families apart; it is never shown.
        Map<String, Family> families = new TreeMap<>();
        for (CommandPanelFeaturePresentation feature : features.values()) {
            if (feature.bonded() == null) continue;
            Map<String, String> attributes = feature.bonded().attributes();
            String limit = attributes.get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT);
            Family family = new Family(
                    BondedCompanionNames.speciesLabel(null, attributes.get(BondedRecords.FAMILY_NAME_KEY),
                            attributes.get(BondedRecords.FAMILY_SOLE_ROLE_ID), language),
                    attributes.get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT),
                    "0".equals(limit) ? "\u221e" : limit,
                    attributes.get(BondedRecords.OWNED_CAPACITY_COUNT),
                    attributes.get(BondedRecords.OWNED_CAPACITY_LIMIT));
            if (family.active() || family.owned()) {
                families.put(attributes.getOrDefault(BondedRecords.FAMILY_ID, attributes.getOrDefault(
                        BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LABEL, "")), family);
            }
        }
        return List.copyOf(families.values());
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

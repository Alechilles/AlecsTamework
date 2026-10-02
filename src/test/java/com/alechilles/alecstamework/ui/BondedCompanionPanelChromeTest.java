package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded.BondedRecords;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.Map;
import java.util.UUID;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import org.junit.jupiter.api.Test;

/** Regression coverage for the dedicated bonded-roster panel chrome. */
class BondedCompanionPanelChromeTest {
    @Test
    void bondedRosterHidesGenericLinkAndGroupControls() {
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionPanelChrome.bind(commands, true);

        assertCommand(commands, "#TameworkLinkedPanelAutoLinkControls.Visible", "false");
        assertCommand(commands, "#TameworkLinkedPanelActiveHighlightControls.Visible", "false");
        assertCommand(commands, "#TameworkLinkedPanelModeDropdown.Visible", "false");
        assertCommand(commands, "#TameworkLinkedPanelSubtitleRow.Visible", "false");
        assertCommand(commands, "#TameworkLinkedPanelGroupAssignOverlay.Visible", "false");
    }

    @Test
    void stateTabsUseDurableRosterStateAndKeepPolicyCapacityIndependentOfVisibleRows() {
        UUID activeId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();
        UUID deadId = UUID.randomUUID();
        LinkedNpcEntry[] entries = {entry(activeId), entry(storedId), entry(deadId)};
        Map<UUID, CommandPanelFeaturePresentation> features = Map.of(
                activeId, feature(BondedCompanionStateView.ACTIVE),
                storedId, feature(BondedCompanionStateView.STORED),
                deadId, feature(BondedCompanionStateView.DEAD));
        assertArrayEquals(new LinkedNpcEntry[]{entries[1]},
                BondedCompanionPanelChrome.filter(entries, features, "Stored"));
        assertArrayEquals(new LinkedNpcEntry[]{entries[2]},
                BondedCompanionPanelChrome.filter(entries, features, "Dead"));
        assertArrayEquals(entries, BondedCompanionPanelChrome.filter(entries, features, "All"));
        // Two active companions count against policy even though only one active row is listed here.
        assertEquals("Active 2 / 3", BondedCompanionPanelChrome.capacityText(features, "en-US"));
    }

    @Test
    void headerShowsActiveAndOwnedCountsAndLeavesOutAnUnlimitedPart() {
        Map<String, String> active = Map.of("bonded.activeCapacity.count", "1", "bonded.activeCapacity.limit", "1");
        Map<String, String> owned = Map.of(BondedRecords.OWNED_CAPACITY_COUNT, "4",
                BondedRecords.OWNED_CAPACITY_LIMIT, "6");
        java.util.HashMap<String, String> both = new java.util.HashMap<>(active);
        both.putAll(owned);

        assertEquals(LocalizedText.format("en-US", "tamework.ui.roster.activeOwnedCapacity", "1", "1", "4", "6"),
                BondedCompanionPanelChrome.capacityText(Map.of(UUID.randomUUID(), feature(both)), "en-US"));
        assertEquals(LocalizedText.format("en-US", "tamework.ui.roster.ownedCapacity", "4", "6"),
                BondedCompanionPanelChrome.capacityText(Map.of(UUID.randomUUID(), feature(owned)), "en-US"));
        assertEquals("", BondedCompanionPanelChrome.capacityText(Map.of(UUID.randomUUID(), feature(Map.of())),
                "en-US"));
    }

    /** A roster family has no display name, so several unnamed families show the label once. */
    @Test
    void severalFamiliesShowEachActiveCountInTheHeaderAndKeepOwnedCountsInTheTooltip() {
        Map<UUID, CommandPanelFeaturePresentation> features = Map.of(
                UUID.randomUUID(), feature(Map.of("bonded.activeCapacity.count", "0",
                        "bonded.activeCapacity.limit", "1", "bonded.activeCapacity.label", "Full Dragons")),
                UUID.randomUUID(), feature(Map.of("bonded.activeCapacity.count", "1",
                        "bonded.activeCapacity.limit", "2", "bonded.activeCapacity.label", "Soulbound Mini",
                        BondedRecords.OWNED_CAPACITY_COUNT, "1", BondedRecords.OWNED_CAPACITY_LIMIT, "3")));

        String header = BondedCompanionPanelChrome.capacityHeader(features, "en-US");
        String tooltip = BondedCompanionPanelChrome.capacityText(features, "en-US");

        assertEquals(LocalizedText.format("en-US", "tamework.ui.roster.activeCapacity", "0", "1") + ", "
                + LocalizedText.format("en-US", "tamework.ui.roster.capacityCount", "1", "2"), header);
        assertEquals(LocalizedText.format("en-US", "tamework.ui.roster.activeCapacity", "0", "1") + "\n"
                + LocalizedText.format("en-US", "tamework.ui.roster.activeOwnedCapacity", "1", "2", "1", "3"),
                tooltip);
        // The label built from the family id tells the families apart but is never shown.
        assertTrue(!header.contains("Dragons") && !tooltip.contains("Soulbound"));
    }

    @Test
    void familiesThatAllowOneRoleAreNamedByThatRoleInTheViewersLanguage() {
        // Any key the language files hold stands in for another mod's role name key.
        String first = "tamework.ui.shared.item";
        String second = "tamework.ui.roster.filter.stored";
        Map<UUID, CommandPanelFeaturePresentation> features = Map.of(
                UUID.randomUUID(), feature(Map.of("bonded.activeCapacity.count", "0",
                        "bonded.activeCapacity.limit", "1", "bonded.activeCapacity.label", "A",
                        BondedRecords.FAMILY_SOLE_ROLE_ID, first)),
                UUID.randomUUID(), feature(Map.of("bonded.activeCapacity.count", "2",
                        "bonded.activeCapacity.limit", "2", "bonded.activeCapacity.label", "B",
                        BondedRecords.FAMILY_SOLE_ROLE_ID, second)));

        for (String language : java.util.List.of("en-US", "de-DE")) {
            assertEquals(LocalizedText.format(language, "tamework.ui.roster.familyCapacity",
                            LocalizedText.resolve(language, first), "0", "1") + " \u00b7 "
                            + LocalizedText.format(language, "tamework.ui.roster.familyCapacity",
                            LocalizedText.resolve(language, second), "2", "2"),
                    BondedCompanionPanelChrome.capacityHeader(features, language));
        }
    }

    private static CommandPanelFeaturePresentation feature(Map<String, String> attributes) {
        return CommandPanelFeaturePresentation.bonded(new BondedCompanionPanelPresentation(
                "profile", "roster", "Wolf", 1L, "Wolf", "Wolf", null, null, attributes, Map.of(),
                new BondedCompanionStatusPresentation(BondedCompanionStateView.STORED,
                        BondedCompanionStatusPresentation.Action.NONE, false, null, null, 0L), null));
    }

    private static LinkedNpcEntry entry(UUID id) {
        return new LinkedNpcEntry(id, "Companion", 10, 10, 0, 0, null,
                0, 0, 0, 0, false, false, false, false, false, false,
                0L, new LinkedNpcTraitIndicator[0]);
    }

    private static CommandPanelFeaturePresentation feature(BondedCompanionStateView state) {
        return CommandPanelFeaturePresentation.bonded(new BondedCompanionPanelPresentation(
                "profile-" + state, "roster", "Wolf", 1L, "Wolf", "Wolf", null, null,
                Map.of("bonded.activeCapacity.count", "2", "bonded.activeCapacity.limit", "3",
                        "bonded.activeCapacity.label", "Wolves"), Map.of(),
                new BondedCompanionStatusPresentation(state,
                        BondedCompanionStatusPresentation.Action.NONE, false, null, null, 0L), null));
    }

    private static void assertCommand(
            UICommandBuilder commands, String selector, String expected
    ) {
        assertTrue(java.util.Arrays.stream(commands.getCommands())
                        .anyMatch(command -> selector.equals(command.selector)
                                && command.data.contains(expected)),
                () -> "Expected " + selector + " to contain " + expected);
    }
}

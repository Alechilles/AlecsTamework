package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
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

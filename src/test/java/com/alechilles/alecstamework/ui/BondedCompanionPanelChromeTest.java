package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import org.junit.jupiter.api.Test;

/** Regression coverage for the dedicated bonded-roster panel chrome. */
class BondedCompanionPanelChromeTest {
    @Test
    void bondedRosterHidesGenericLinkAndGroupControls() {
        UICommandBuilder commands = new UICommandBuilder();

        BondedCompanionPanelChrome.bind(commands, true);

        assertCommand(commands, "#TameworkLinkedPanelAutoLinkControls.Visible", "false");
        assertCommand(commands, "#TameworkLinkedPanelModeDropdown.Visible", "false");
        assertCommand(commands, "#TameworkLinkedPanelSubtitleRow.Visible", "false");
        assertCommand(commands, "#TameworkLinkedPanelGroupAssignOverlay.Visible", "false");
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

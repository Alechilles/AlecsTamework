package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LinkedNpcPanelPresentationSupportTest {
    @Test
    void filterResizingUsesAnchorObjectsAndPreservesToolbarPlacement() {
        // Nested Anchor.Width selectors disconnected the client when the command panel opened.
        for (String mode : List.of(TameworkCommandSelectionPage.PANEL_MODE_LINKED,
                TameworkCommandSelectionPage.PANEL_MODE_OWNED, TameworkCommandSelectionPage.PANEL_MODE_NEARBY)) {
            UICommandBuilder commands = new UICommandBuilder();
            LinkedNpcPanelPresentationSupport.bindFilterWidth(commands, () -> mode);
            int width = mode.equals(TameworkCommandSelectionPage.PANEL_MODE_NEARBY) ? 96 : 200;
            assertAnchor(commands, "#TameworkLinkedPanelControlsSecondary", 264 + width, true);
            assertAnchor(commands, "#TameworkLinkedPanelInlineFilterTextControls", width, false);
            assertAnchor(commands, "#TameworkLinkedPanelFilterInput", width, false);
        }
    }

    private static void assertAnchor(UICommandBuilder actual, String selector, int width, boolean toolbar) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(28));
        if (toolbar) {
            anchor.setLeft(Value.of(444));
            anchor.setTop(Value.of(0));
        }
        UICommandBuilder expected = new UICommandBuilder();
        expected.setObject(selector + ".Anchor", anchor);
        var emitted = Arrays.stream(actual.getCommands())
                .filter(command -> command.selector.startsWith(selector + "."))
                .toList();
        Assertions.assertEquals(1, emitted.size());
        Assertions.assertEquals(expected.getCommands()[0].selector, emitted.getFirst().selector);
        Assertions.assertEquals(expected.getCommands()[0].data, emitted.getFirst().data);
    }
}

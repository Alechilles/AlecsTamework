package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedCompanionAbandonPresentationTest {
    /** Off-screen unlinked animals must offer confirmed abandonment without live-only actions. */
    @Test
    void unloadedUnlinkedAnimalOffersAbandonAfterOpeningRemovalControls() {
        var entry = entry(false);
        var initial = render(entry, false);
        assertValue(initial, "RemoveButton.Visible", "true");
        assertValue(initial, "ReleaseButton.Visible", "false");
        var confirmation = render(entry, true);
        assertValue(confirmation, "ReleaseButton.Visible", "true");
        assertValue(confirmation, "ReleaseButton.Text", "Abandon");
        assertValue(confirmation, "CullButton.Visible", "false");
        assertValue(confirmation, "LinkButton.Visible", "false");
    }

    /** Capture storage must be released through its own lifecycle before abandonment. */
    @Test
    void capturedUnlinkedAnimalDoesNotOfferAbandon() {
        assertValue(render(entry(true), true), "ReleaseButton.Visible", "false");
    }

    private static LinkedNpcEntry entry(boolean captured) {
        return new LinkedNpcEntry(UUID.randomUUID(), "Cow", 0, 0, 0, 0, 0, null,
                0, 0, 0, 0, false, false, false, captured, false, false, 0L,
                null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, false, true,
                "Cow", "Cow", null, null, null, false, false, 0L, 0.0, false);
    }

    private static UICommandBuilder render(LinkedNpcEntry entry, boolean confirm) {
        UICommandBuilder commands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(commands, new UIEventBuilder(), 0, entry,
                false, confirm, LinkedNpcPanelCardBindingFactory.create(true, false), "en-US");
        return commands;
    }

    private static void assertValue(UICommandBuilder commands, String suffix, String value) {
        String selector = "#TameworkLinkedPanelList[0] #" + suffix;
        assertTrue(Arrays.stream(commands.getCommands()).anyMatch(command ->
                selector.equals(command.selector) && command.data.contains(value)), selector);
    }
}

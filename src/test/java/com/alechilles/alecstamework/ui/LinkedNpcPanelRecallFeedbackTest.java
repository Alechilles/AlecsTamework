package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkedNpcPanelRecallFeedbackTest {
    /** Saved location details must not hide an in-progress recall in either ordinary tab. */
    @Test void pendingRecallRemainsVisibleWithInlineLocationAndUpdatesUntilFinished() {
        for (boolean linked : new boolean[] {true, false}) {
            var pending = entry(linked, true, 9_500L);
            var commands = render(pending);
            assertValue(commands, "#InlineLocation.Visible", "true");
            assertValue(commands, "#RecallCountdown.Visible", "true");
            assertValue(commands, "#RecallCountdown.Text", "Attempting recall: 10s...");
            var location = anchor(commands, "#InlineLocation.Anchor");
            var recall = anchor(commands, "#RecallButton.Anchor");
            assertTrue(location.getNumber("Left").intValue() + location.getNumber("Width").intValue()
                    < recall.getNumber("Left").intValue());
            assertValue(commands, "#RecallButtonCaption.Visible", "false");
            if (linked) {
                assertValue(commands, "#ReturnHomeButton.Visible", "true");
                assertValue(commands, "#ReturnHomeButtonCaption.Visible", "false");
            }

            var update = new UICommandBuilder();
            LinkedNpcPanelCardDynamicPresenter.refresh(update, new UIEventBuilder(),
                    "#TameworkLinkedPanelList[0]", pending.npcUuid(), pending,
                    entry(linked, true, 8_500L), null, null, false,
                    LinkedNpcPanelCardBindingFactory.create(true, false), "en-US");
            assertValue(update, "#RecallCountdown.Text", "Attempting recall: 9s...");
            assertValue(render(entry(linked, false, 0L)), "#RecallCountdown.Visible", "false");
        }
    }

    private static org.bson.BsonDocument anchor(UICommandBuilder commands, String suffix) {
        return org.bson.BsonDocument.parse(Arrays.stream(commands.getCommands())
                .filter(command -> ("#TameworkLinkedPanelList[0] " + suffix).equals(command.selector))
                .reduce((first, last) -> last).orElseThrow().data).getDocument("0");
    }

    private static LinkedNpcEntry entry(boolean linked, boolean pending, long remaining) {
        return new LinkedNpcEntry(UUID.randomUUID(), "Frost Dragon", null,
                0, 0, 0, 0, 0, null, 0, 0, 0, 0,
                false, true, false, false, false, false, 0L,
                null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, linked, true,
                "Dragon", "Dragon", null, null, null,
                false, false, false, 0L, 0.0, false,
                false, 0L, 0.0, false, pending, remaining)
                .withOwnedActions().withLocation(new LinkedNpcEntry.Location("", "default", "1, 2, 3"));
    }

    private static UICommandBuilder render(LinkedNpcEntry entry) {
        var commands = new UICommandBuilder();
        LinkedNpcPanelCardBinder.bind(commands, new UIEventBuilder(), 0, entry,
                false, false, LinkedNpcPanelCardBindingFactory.create(true, false), "en-US");
        return commands;
    }

    private static void assertValue(UICommandBuilder commands, String suffix, String value) {
        assertTrue(Arrays.stream(commands.getCommands()).anyMatch(command ->
                ("#TameworkLinkedPanelList[0] " + suffix).equals(command.selector)
                        && command.data.contains(value)), suffix + " = " + value);
    }
}

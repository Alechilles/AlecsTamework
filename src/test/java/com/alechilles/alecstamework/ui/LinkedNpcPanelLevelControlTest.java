package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Arrays;
import java.util.List;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkedNpcPanelLevelControlTest {
    @Test
    void panelAndGuideLevelButtonsKeepTheirTextAndFitLargeLevelsWithoutRingUpdates() {
        for (String card : List.of("#TameworkLinkedPanelList[0]", "#GuideExample[0]")) {
            String ring = card + " #XpProgressRing";
            UICommandBuilder commands = new UICommandBuilder();
            LinkedNpcPanelProgressionBinder.bindXpProgressRing(commands, ring,
                    ring + " #XpLevelText", ring + " #XpTooltip",
                    new LinkedNpcEntry.FutureStat("Level 123", 40, 100, "Bonus"));
            assertEquals("123", value(commands, ring + " #XpLevelText.Text").getString("0").getValue());
            assertEquals("40/100 XP\nBonus", value(commands, ring + " #XpTooltip.TooltipText").getString("0").getValue());
            assertTrue(value(commands, ring + ".Anchor").toJson().contains("64"));
            assertFalse(Arrays.stream(commands.getCommands()).anyMatch(command ->
                    command.selector.contains("#RingFillBar")), "The level-button asset has no ring widgets.");
        }
    }

    private static BsonDocument value(UICommandBuilder commands, String selector) {
        return BsonDocument.parse(Arrays.stream(commands.getCommands())
                .filter(command -> selector.equals(command.selector)).findFirst().orElseThrow().data);
    }
}

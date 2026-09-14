package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedNpcLocationCopyControlTest {
    @Test
    void togglesRawCoordinatesAndKeepsTheSelectionModeAcrossCardRefreshes() {
        var entry = entry();
        var control = new LinkedNpcLocationCopyControl();
        var commands = new UICommandBuilder();
        String path = "#TameworkLinkedPanelList[0] #InlineLocation";
        control.bind(commands, 0, entry);
        assertCommand(commands, path + " #Coordinates.Visible", false);
        assertCommand(commands, path + " #CoordinateLabel.Visible", true);
        assertCommand(commands, path + " #RelativeDistance.Visible", true);
        control.toggle(LinkedNpcLocationCopyControl.PREFIX + entry.npcUuid(), new LinkedNpcEntry[] {entry}, commands);
        assertCommand(commands, path + " #Coordinates.Visible", true);
        assertCommand(commands, path + " #CoordinateLabel.Visible", false);
        assertCommand(commands, path + " #RelativeDistance.Visible", false);
        UICommandBuilder expected = new UICommandBuilder();
        expected.set(path + " #Coordinates.Value", "-3990.0, 120.0, -2533.0");
        assertEquals(expected.getCommands()[0].data, latest(commands, path + " #Coordinates.Value"));
        control.bind(commands, 0, entry.withOwnedActions());
        assertCommand(commands, path + " #Coordinates.Visible", true);
        control.toggle(LinkedNpcLocationCopyControl.PREFIX + entry.npcUuid(), new LinkedNpcEntry[] {entry}, commands);
        assertCommand(commands, path + " #Coordinates.Visible", false);
        assertCommand(commands, path + " #CoordinateLabel.Visible", true);
        assertCommand(commands, path + " #RelativeDistance.Visible", true);
    }

    @Test
    void labelsAxesWithoutChangingTheCopyableTuple() {
        assertEquals("X: -3990.0   Y: 120.0   Z: -2533.0",
                LinkedNpcLocationCopyControl.labeledCoordinates(entry().location().coordinates()));
    }

    @Test
    void ignoresAnUnknownCard() {
        var commands = new UICommandBuilder();
        new LinkedNpcLocationCopyControl().toggle(LinkedNpcLocationCopyControl.PREFIX + UUID.randomUUID(),
                new LinkedNpcEntry[] {entry()}, commands);
        assertEquals(0, commands.getCommands().length);
    }

    private static LinkedNpcEntry entry() {
        return new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 0, 0, 0, 0, "", 0, 0, 0, 0,
                false, false, false, true, false, false, 0L, LinkedNpcTraitIndicator.EMPTY)
                .withLocation(new LinkedNpcEntry.Location("Soul Lantern in Wooden Chest", "default",
                        "-3990.0, 120.0, -2533.0", "1550m north, 780m west"));
    }

    private static void assertCommand(UICommandBuilder commands, String selector, boolean value) {
        var expected = new UICommandBuilder();
        expected.set(selector, value);
        assertEquals(expected.getCommands()[0].data, latest(commands, selector));
    }

    private static String latest(UICommandBuilder commands, String selector) {
        return Arrays.stream(commands.getCommands()).filter(command -> selector.equals(command.selector))
                .reduce((first, last) -> last).orElseThrow().data;
    }
}

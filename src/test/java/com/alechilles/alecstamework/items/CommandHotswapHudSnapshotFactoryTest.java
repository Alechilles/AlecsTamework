package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the hotswap service model reaches every detached HUD field. */
class CommandHotswapHudSnapshotFactoryTest {
    @Test
    void copiesAllFiveControlsAndGroupStatus() {
        CommandHotswapHudViewModel model = new CommandHotswapHudViewModel(
                slot("LMB", "Items/Primary.png", "P"),
                slot("RMB", "Items/Secondary.png", "S"),
                slot("Q", "Items/Q.png", "Q"),
                slot("E", "Items/E.png", "E"),
                slot("R", "Items/R.png", "R"),
                new CommandHotswapHudViewModel.GroupStatus(true, "Blue Squad", "#112233")
        );

        CommandHotswapHudSnapshot snapshot = new CommandHotswapHudSnapshotFactory().create(model);

        assertEquals("Items/Primary.png", snapshot.primary().iconTexturePath());
        assertEquals("S", snapshot.secondary().fallbackGlyph());
        assertEquals("Q", snapshot.q().bindingLabel());
        assertEquals("Items/E.png", snapshot.e().iconTexturePath());
        assertEquals("R", snapshot.r().fallbackGlyph());
        assertTrue(snapshot.groupStatus().visible());
        assertEquals("Blue Squad", snapshot.groupStatus().label());
        assertEquals("#112233", snapshot.groupStatus().colorHex());
        assertTrue(snapshot.visible());
    }

    @Test
    void preservesHiddenControlsAndHiddenGroupStatus() {
        CommandHotswapHudSnapshot snapshot = new CommandHotswapHudSnapshotFactory().create(
                new CommandHotswapHudViewModel(
                        CommandHotswapHudViewModel.Slot.hidden("LMB"),
                        CommandHotswapHudViewModel.Slot.hidden("RMB"),
                        CommandHotswapHudViewModel.Slot.hidden("Q"),
                        CommandHotswapHudViewModel.Slot.hidden("E"),
                        CommandHotswapHudViewModel.Slot.hidden("R"),
                        CommandHotswapHudViewModel.GroupStatus.hidden()));

        assertFalse(snapshot.visible());
        assertFalse(snapshot.groupStatus().visible());
        assertEquals("R", snapshot.r().bindingLabel());
    }

    private static CommandHotswapHudViewModel.Slot slot(
            String binding, String icon, String glyph
    ) {
        return new CommandHotswapHudViewModel.Slot(true, binding, icon, glyph);
    }
}

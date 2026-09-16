package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedNpcTraitIndicatorBinderTest {
    // Catches the old hidden-ring segments making maxed traits appear only partly filled.
    @Test
    void traitBarsShowTheirWholeRangeForPositiveAndNegativeTraits() {
        assertFill(1.0, false, 22);
        assertFill(0.5, false, 11);
        assertFill(1.0, true, 22);
        assertFill(0.25, true, 6);
        assertFill(0.0, false, 0);
    }

    private static void assertFill(double ratio, boolean negative, int width) {
        UICommandBuilder actual = new UICommandBuilder();
        LinkedNpcTraitIndicatorBinder.bind(actual, "#Card", new LinkedNpcTraitIndicator[] {
                new LinkedNpcTraitIndicator("S", null, "Size", "Size", ratio, !negative, negative)
        });
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(6));
        anchor.setTop(Value.of(1));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(3));
        UICommandBuilder expected = new UICommandBuilder();
        expected.setObject("#Card #TraitSlot0 #RingFillBar1.Anchor", anchor);
        expected.set("#Card #TraitSlot0 #RingFillBar1.Background", negative ? "#d45f5f" : "#6fc576");
        for (var command : expected.getCommands()) {
            String emitted = null;
            for (var candidate : actual.getCommands()) {
                if (candidate.selector.equals(command.selector)) emitted = candidate.data;
            }
            assertEquals(command.data, emitted, command.selector);
        }
    }
}

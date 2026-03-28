package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Applies linked-companion trait indicator state to UI card selectors.
 */
final class LinkedNpcTraitIndicatorBinder {
    static final int MAX_VISIBLE_TRAIT_INDICATORS = 4;

    private static final double RING_BAR1_LENGTH = 12.0;
    private static final double RING_BAR2_LENGTH = 24.0;
    private static final double RING_BAR3_LENGTH = 24.0;
    private static final double RING_BAR4_LENGTH = 24.0;
    private static final double RING_BAR5_LENGTH = 12.0;
    private static final double RING_PERIMETER_PIXELS =
            RING_BAR1_LENGTH + RING_BAR2_LENGTH + RING_BAR3_LENGTH + RING_BAR4_LENGTH + RING_BAR5_LENGTH;
    private static final String POSITIVE_FILL_COLOR = "#6fc576";
    private static final String NEGATIVE_FILL_COLOR = "#d45f5f";

    private LinkedNpcTraitIndicatorBinder() {
    }

    static void bind(UICommandBuilder commandBuilder,
                     String entrySelector,
                     LinkedNpcTraitIndicator[] indicators) {
        LinkedNpcTraitIndicator[] safeIndicators = indicators == null ? LinkedNpcTraitIndicator.EMPTY : indicators;
        for (int index = 0; index < MAX_VISIBLE_TRAIT_INDICATORS; index++) {
            String slotSelector = entrySelector + " #TraitSlot" + index;
            LinkedNpcTraitIndicator indicator = index < safeIndicators.length ? safeIndicators[index] : null;
            if (indicator == null) {
                commandBuilder.set(slotSelector + ".Visible", false);
                continue;
            }
            commandBuilder.set(slotSelector + ".Visible", true);
            bindSlot(commandBuilder, slotSelector, indicator);
        }
    }

    private static void bindSlot(UICommandBuilder commandBuilder,
                                 String slotSelector,
                                 LinkedNpcTraitIndicator indicator) {
        String fillColor = indicator.belowDefault() ? NEGATIVE_FILL_COLOR : POSITIVE_FILL_COLOR;
        if (indicator.hasIconTexturePath()) {
            commandBuilder.set(slotSelector + " #TraitIcon.Visible", false);
            commandBuilder.set(slotSelector + " #TraitIconImage.Visible", true);
            commandBuilder.set(
                    slotSelector + " #TraitIconImage.Background",
                    indicator.iconTexturePath()
            );
        } else {
            commandBuilder.set(slotSelector + " #TraitIconImage.Visible", false);
            commandBuilder.set(slotSelector + " #TraitIcon.Visible", true);
            commandBuilder.set(slotSelector + " #TraitIcon.Text", indicator.iconText());
        }
        commandBuilder.set(slotSelector + " #TraitTooltip.TooltipText", indicator.tooltipText());
        commandBuilder.set(slotSelector + " #RingFillBar1.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillBar2.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillBar3.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillBar4.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillBar5.Background", fillColor);

        SegmentFill fill = resolveSegmentFill(indicator.fillRatio());
        commandBuilder.setObject(
                slotSelector + " #RingFillBar1.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitRingBar1Anchor(fill.bar1(), indicator.counterClockwise())
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillBar2.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitRingBar2Anchor(fill.bar2(), indicator.counterClockwise())
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillBar3.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitRingBar3Anchor(fill.bar3(), indicator.counterClockwise())
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillBar4.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitRingBar4Anchor(fill.bar4(), indicator.counterClockwise())
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillBar5.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitRingBar5Anchor(fill.bar5(), indicator.counterClockwise())
        );
    }

    private static SegmentFill resolveSegmentFill(double fillRatio) {
        int coveredPixels = (int) Math.floor(clamp(fillRatio) * RING_PERIMETER_PIXELS + 1.0e-9);
        int remaining = coveredPixels;
        int bar1 = consumePixels(remaining, (int) RING_BAR1_LENGTH);
        remaining -= bar1;
        int bar2 = consumePixels(remaining, (int) RING_BAR2_LENGTH);
        remaining -= bar2;
        int bar3 = consumePixels(remaining, (int) RING_BAR3_LENGTH);
        remaining -= bar3;
        int bar4 = consumePixels(remaining, (int) RING_BAR4_LENGTH);
        remaining -= bar4;
        int bar5 = consumePixels(remaining, (int) RING_BAR5_LENGTH);
        return new SegmentFill(bar1, bar2, bar3, bar4, bar5);
    }

    private static int consumePixels(int remaining, int segmentLength) {
        return Math.max(0, Math.min(segmentLength, remaining));
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record SegmentFill(int bar1, int bar2, int bar3, int bar4, int bar5) {
    }
}

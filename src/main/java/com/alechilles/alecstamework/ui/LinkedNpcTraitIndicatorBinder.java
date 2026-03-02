package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Applies linked-companion trait indicator state to UI card selectors.
 */
final class LinkedNpcTraitIndicatorBinder {
    static final int MAX_VISIBLE_TRAIT_INDICATORS = 3;

    private static final double RING_SEGMENT_MULTIPLIER = 4.0;
    private static final int RING_SEGMENT_PIXELS = 18;
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
        commandBuilder.set(slotSelector + " #RingFillTop.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillRight.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillBottom.Background", fillColor);
        commandBuilder.set(slotSelector + " #RingFillLeft.Background", fillColor);

        SegmentFill fill = resolveSegmentFill(indicator.fillRatio(), indicator.counterClockwise());
        commandBuilder.setObject(
                slotSelector + " #RingFillTop.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitTopFillAnchor(toPixels(fill.top), indicator.counterClockwise())
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillRight.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitRightFillAnchor(toPixels(fill.right), indicator.counterClockwise())
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillBottom.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitBottomFillAnchor(toPixels(fill.bottom), indicator.counterClockwise())
        );
        commandBuilder.setObject(
                slotSelector + " #RingFillLeft.Anchor",
                LinkedNpcPanelAnchorFactory.buildTraitLeftFillAnchor(toPixels(fill.left), indicator.counterClockwise())
        );
    }

    private static SegmentFill resolveSegmentFill(double fillRatio, boolean counterClockwise) {
        double scaled = clamp(fillRatio) * RING_SEGMENT_MULTIPLIER;
        double first = resolveSegmentRatio(scaled, 0.0);
        double second = resolveSegmentRatio(scaled, 1.0);
        double third = resolveSegmentRatio(scaled, 2.0);
        double fourth = resolveSegmentRatio(scaled, 3.0);
        if (counterClockwise) {
            return new SegmentFill(first, fourth, third, second);
        }
        return new SegmentFill(first, second, third, fourth);
    }

    private static double resolveSegmentRatio(double scaledRatio, double segmentIndex) {
        return clamp(scaledRatio - segmentIndex);
    }

    private static int toPixels(double segmentRatio) {
        return (int) Math.round(clamp(segmentRatio) * RING_SEGMENT_PIXELS);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record SegmentFill(double top, double right, double bottom, double left) {
    }
}

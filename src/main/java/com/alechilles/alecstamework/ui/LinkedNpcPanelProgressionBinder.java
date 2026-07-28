package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Applies compact linked-panel progression indicators to UI controls.
 */
final class LinkedNpcPanelProgressionBinder {
    private LinkedNpcPanelProgressionBinder() {
    }

    static void bindXpProgressRing(UICommandBuilder commandBuilder,
                                   String ringSelector,
                                   String levelTextSelector,
                                   String tooltipSelector,
                                   LinkedNpcEntry.FutureStat stat) {
        commandBuilder.set(ringSelector + ".Visible", stat != null);
        if (stat == null) {
            return;
        }
        commandBuilder.set(levelTextSelector + ".Text", resolveLevelText(stat.label()));
        commandBuilder.set(tooltipSelector + ".TooltipText", resolveXpTooltip(stat));
        LinkedNpcPanelRingFill.SegmentFill fill = LinkedNpcPanelRingFill.resolve(progressRatio(stat.current(), stat.max()));
        commandBuilder.setObject(ringSelector + " #RingFillBar1.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar1Anchor(fill.bar1()));
        commandBuilder.setObject(ringSelector + " #RingFillBar2.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar2Anchor(fill.bar2()));
        commandBuilder.setObject(ringSelector + " #RingFillBar3.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar3Anchor(fill.bar3()));
        commandBuilder.setObject(ringSelector + " #RingFillBar4.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar4Anchor(fill.bar4()));
        commandBuilder.setObject(ringSelector + " #RingFillBar5.Anchor", LinkedNpcPanelAnchorFactory.buildNeedRingBar5Anchor(fill.bar5()));
    }

    static void bindTalentPointIndicator(UICommandBuilder commandBuilder,
                                         String actionSelector,
                                         String countSelector,
                                         String countShadowSelector,
                                         LinkedNpcEntry.FutureStat stat,
                                         boolean visible) {
        commandBuilder.set(actionSelector + ".Visible", visible);
        if (!visible) {
            return;
        }
        String text = Integer.toString(availableTalentPoints(stat));
        commandBuilder.set(countSelector + ".Text", text);
        commandBuilder.set(countShadowSelector + ".Text", text);
    }

    static int availableTalentPoints(LinkedNpcEntry.FutureStat stat) {
        return stat == null ? 0 : Math.max(0, stat.current());
    }

    private static String resolveLevelText(String label) {
        if (label == null || label.isBlank()) {
            return "?";
        }
        String[] parts = label.trim().split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("Level".equalsIgnoreCase(parts[i]) && parts[i + 1].matches("\\d+")) {
                return parts[i + 1];
            }
        }
        for (String part : parts) {
            if (part.matches("\\d+")) {
                return part;
            }
        }
        return "?";
    }

    static String resolveXpTooltip(LinkedNpcEntry.FutureStat stat) {
        String detail = stat.tooltipText();
        String suffix = detail == null || detail.isBlank() ? "" : "\n" + detail.trim();
        String header = stat.tooltipHeaderText();
        if (header != null && !header.isBlank()) {
            return header.trim() + suffix;
        }
        if (stat.label() != null && stat.label().toUpperCase().contains("MAX")) {
            return stat.label() + suffix;
        }
        return stat.current() + "/" + stat.max() + " XP" + suffix;
    }

    private static double progressRatio(int current, int max) {
        if (max <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, ((double) current) / (double) max));
    }
}

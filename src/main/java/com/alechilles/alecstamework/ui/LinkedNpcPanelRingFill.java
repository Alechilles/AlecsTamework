package com.alechilles.alecstamework.ui;

/**
 * Maps progress ratios onto the segmented 24x24 linked-panel progress ring.
 */
final class LinkedNpcPanelRingFill {
    private static final double RING_BAR1_LENGTH = 12.0;
    private static final double RING_BAR2_LENGTH = 24.0;
    private static final double RING_BAR3_LENGTH = 24.0;
    private static final double RING_BAR4_LENGTH = 24.0;
    private static final double RING_BAR5_LENGTH = 12.0;
    private static final double RING_PERIMETER_PIXELS =
            RING_BAR1_LENGTH + RING_BAR2_LENGTH + RING_BAR3_LENGTH + RING_BAR4_LENGTH + RING_BAR5_LENGTH;

    private LinkedNpcPanelRingFill() {
    }

    static SegmentFill resolve(double fillRatio) {
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
        return new SegmentFill(
                bar1,
                bar2,
                bar3,
                bar4,
                bar5
        );
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

    record SegmentFill(int bar1, int bar2, int bar3, int bar4, int bar5) {
    }
}

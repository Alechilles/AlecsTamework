package com.alechilles.alecstamework.ui;

/**
 * Immutable trait indicator model rendered in linked companion cards.
 */
public final class LinkedNpcTraitIndicator {
    public static final LinkedNpcTraitIndicator[] EMPTY = new LinkedNpcTraitIndicator[0];

    private final String iconText;
    private final String label;
    private final String tooltipText;
    private final double fillRatio;
    private final boolean counterClockwise;
    private final boolean belowDefault;

    public LinkedNpcTraitIndicator(String iconText,
                                   String label,
                                   String tooltipText,
                                   double fillRatio,
                                   boolean counterClockwise,
                                   boolean belowDefault) {
        this.iconText = sanitizeText(iconText, "?");
        this.label = sanitizeText(label, "Trait");
        this.tooltipText = sanitizeText(tooltipText, this.label);
        this.fillRatio = clamp(fillRatio);
        this.counterClockwise = counterClockwise;
        this.belowDefault = belowDefault;
    }

    public String iconText() {
        return iconText;
    }

    public String label() {
        return label;
    }

    public String tooltipText() {
        return tooltipText;
    }

    public double fillRatio() {
        return fillRatio;
    }

    public boolean counterClockwise() {
        return counterClockwise;
    }

    public boolean belowDefault() {
        return belowDefault;
    }

    private static String sanitizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

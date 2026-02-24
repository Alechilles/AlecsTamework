package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

/**
 * Builds anchor values for linked NPC panel elements.
 */
final class LinkedNpcPanelAnchorFactory {
    private static final int TRAIT_RING_H_SEGMENT_LENGTH = 18;
    private static final int TRAIT_RING_V_SEGMENT_LENGTH = 18;
    private static final int TRAIT_RING_TOP_Y = 1;
    private static final int TRAIT_RING_BOTTOM_Y = 21;
    private static final int TRAIT_RING_LEFT_X = 1;
    private static final int TRAIT_RING_RIGHT_X = 21;
    private static final int TRAIT_RING_H_SEGMENT_LEFT = 3;
    private static final int TRAIT_RING_V_SEGMENT_TOP = 3;

    private LinkedNpcPanelAnchorFactory() {
    }

    static Anchor buildHealthFillAnchor(double ratio, int maxWidth) {
        int width = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * maxWidth);
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(1));
        anchor.setTop(Value.of(1));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(12));
        return anchor;
    }

    static Anchor buildTraitTopFillAnchor(int width, boolean counterClockwise) {
        int clamped = clamp(width, TRAIT_RING_H_SEGMENT_LENGTH);
        int left = counterClockwise
                ? TRAIT_RING_H_SEGMENT_LEFT + (TRAIT_RING_H_SEGMENT_LENGTH - clamped)
                : TRAIT_RING_H_SEGMENT_LEFT;
        return buildAnchor(left, TRAIT_RING_TOP_Y, clamped, 2);
    }

    static Anchor buildTraitRightFillAnchor(int height, boolean counterClockwise) {
        int clamped = clamp(height, TRAIT_RING_V_SEGMENT_LENGTH);
        int top = counterClockwise
                ? TRAIT_RING_V_SEGMENT_TOP + (TRAIT_RING_V_SEGMENT_LENGTH - clamped)
                : TRAIT_RING_V_SEGMENT_TOP;
        return buildAnchor(TRAIT_RING_RIGHT_X, top, 2, clamped);
    }

    static Anchor buildTraitBottomFillAnchor(int width, boolean counterClockwise) {
        int clamped = clamp(width, TRAIT_RING_H_SEGMENT_LENGTH);
        int left = counterClockwise
                ? TRAIT_RING_H_SEGMENT_LEFT
                : TRAIT_RING_H_SEGMENT_LEFT + (TRAIT_RING_H_SEGMENT_LENGTH - clamped);
        return buildAnchor(left, TRAIT_RING_BOTTOM_Y, clamped, 2);
    }

    static Anchor buildTraitLeftFillAnchor(int height, boolean counterClockwise) {
        int clamped = clamp(height, TRAIT_RING_V_SEGMENT_LENGTH);
        int top = counterClockwise
                ? TRAIT_RING_V_SEGMENT_TOP
                : TRAIT_RING_V_SEGMENT_TOP + (TRAIT_RING_V_SEGMENT_LENGTH - clamped);
        return buildAnchor(TRAIT_RING_LEFT_X, top, 2, clamped);
    }

    private static Anchor buildAnchor(int left, int top, int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(Math.max(0, left)));
        anchor.setTop(Value.of(Math.max(0, top)));
        anchor.setWidth(Value.of(Math.max(0, width)));
        anchor.setHeight(Value.of(Math.max(0, height)));
        return anchor;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }
}

package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;

/**
 * Builds anchor values for linked NPC panel elements.
 */
final class LinkedNpcPanelAnchorFactory {
    private static final int NEED_RING_THICKNESS = 3;
    private static final int NEED_RING_TOP_Y = 0;
    private static final int NEED_RING_SIDE_TOP = 0;
    private static final int NEED_RING_BOTTOM_Y = 21;
    private static final int NEED_RING_LEFT_X = 0;
    private static final int NEED_RING_RIGHT_X = 21;
    private static final int NEED_RING_TOP_LEFT_START = 0;
    private static final int NEED_RING_TOP_RIGHT_START = 12;
    private static final int NEED_RING_TOP_HALF_LENGTH = 12;
    private static final int NEED_RING_SIDE_LENGTH = 24;
    private static final int NEED_RING_BOTTOM_LENGTH = 24;

    private static final int TRAIT_RING_THICKNESS = 3;
    private static final int TRAIT_RING_TOP_Y = 0;
    private static final int TRAIT_RING_SIDE_TOP = 0;
    private static final int TRAIT_RING_BOTTOM_Y = 21;
    private static final int TRAIT_RING_LEFT_X = 0;
    private static final int TRAIT_RING_RIGHT_X = 21;
    private static final int TRAIT_RING_TOP_LEFT_START = 0;
    private static final int TRAIT_RING_TOP_RIGHT_START = 12;
    private static final int TRAIT_RING_TOP_HALF_LENGTH = 12;
    private static final int TRAIT_RING_SIDE_LENGTH = 24;
    private static final int TRAIT_RING_BOTTOM_LENGTH = 24;

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

    static Anchor buildTraitRingBar1Anchor(int width, boolean counterClockwise) {
        int clamped = clamp(width, TRAIT_RING_TOP_HALF_LENGTH);
        int left = counterClockwise
                ? TRAIT_RING_TOP_LEFT_START + (TRAIT_RING_TOP_HALF_LENGTH - clamped)
                : TRAIT_RING_TOP_RIGHT_START;
        return buildAnchor(left, TRAIT_RING_TOP_Y, clamped, TRAIT_RING_THICKNESS);
    }

    static Anchor buildTraitRingBar2Anchor(int height, boolean counterClockwise) {
        int clamped = clamp(height, TRAIT_RING_SIDE_LENGTH);
        int left = counterClockwise ? TRAIT_RING_LEFT_X : TRAIT_RING_RIGHT_X;
        return buildAnchor(left, TRAIT_RING_SIDE_TOP, TRAIT_RING_THICKNESS, clamped);
    }

    static Anchor buildTraitRingBar3Anchor(int width, boolean counterClockwise) {
        int clamped = clamp(width, TRAIT_RING_BOTTOM_LENGTH);
        int left = counterClockwise
                ? TRAIT_RING_TOP_LEFT_START
                : TRAIT_RING_TOP_LEFT_START + (TRAIT_RING_BOTTOM_LENGTH - clamped);
        return buildAnchor(left, TRAIT_RING_BOTTOM_Y, clamped, TRAIT_RING_THICKNESS);
    }

    static Anchor buildTraitRingBar4Anchor(int height, boolean counterClockwise) {
        int clamped = clamp(height, TRAIT_RING_SIDE_LENGTH);
        int left = counterClockwise ? TRAIT_RING_RIGHT_X : TRAIT_RING_LEFT_X;
        int top = TRAIT_RING_SIDE_TOP + (TRAIT_RING_SIDE_LENGTH - clamped);
        return buildAnchor(left, top, TRAIT_RING_THICKNESS, clamped);
    }

    static Anchor buildTraitRingBar5Anchor(int width, boolean counterClockwise) {
        int clamped = clamp(width, TRAIT_RING_TOP_HALF_LENGTH);
        int left = counterClockwise
                ? TRAIT_RING_TOP_RIGHT_START + (TRAIT_RING_TOP_HALF_LENGTH - clamped)
                : TRAIT_RING_TOP_LEFT_START;
        return buildAnchor(left, TRAIT_RING_TOP_Y, clamped, TRAIT_RING_THICKNESS);
    }

    static Anchor buildNeedRingBar1Anchor(int width) {
        int clamped = clamp(width, NEED_RING_TOP_HALF_LENGTH);
        int left = NEED_RING_TOP_LEFT_START + (NEED_RING_TOP_HALF_LENGTH - clamped);
        return buildAnchor(left, NEED_RING_TOP_Y, clamped, NEED_RING_THICKNESS);
    }

    static Anchor buildNeedRingBar2Anchor(int height) {
        int clamped = clamp(height, NEED_RING_SIDE_LENGTH);
        return buildAnchor(NEED_RING_LEFT_X, NEED_RING_SIDE_TOP, NEED_RING_THICKNESS, clamped);
    }

    static Anchor buildNeedRingBar3Anchor(int width) {
        int clamped = clamp(width, NEED_RING_BOTTOM_LENGTH);
        return buildAnchor(NEED_RING_TOP_LEFT_START, NEED_RING_BOTTOM_Y, clamped, NEED_RING_THICKNESS);
    }

    static Anchor buildNeedRingBar4Anchor(int height) {
        int clamped = clamp(height, NEED_RING_SIDE_LENGTH);
        int top = NEED_RING_SIDE_TOP + (NEED_RING_SIDE_LENGTH - clamped);
        return buildAnchor(NEED_RING_RIGHT_X, top, NEED_RING_THICKNESS, clamped);
    }

    static Anchor buildNeedRingBar5Anchor(int width) {
        int clamped = clamp(width, NEED_RING_TOP_HALF_LENGTH);
        int left = NEED_RING_TOP_RIGHT_START + (NEED_RING_TOP_HALF_LENGTH - clamped);
        return buildAnchor(left, NEED_RING_TOP_Y, clamped, NEED_RING_THICKNESS);
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

package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One player's position for the companion target HUD: a snap point on the screen plus an offset from it, in HUD
 * canvas units (+x right, +y down). The canvas is always 1080 units tall; its width depends on the client's aspect
 * ratio, which the server never learns.
 *
 * <p>The HUD is laid out as a full-height lane holding the plate. The lane's width is a {@code MaxWidth}, which the
 * client caps at the screen width, and the plate sits against the lane's far side. The plate therefore follows its
 * offset until it meets a screen edge and stops there, on any aspect ratio (the Rune UI HUD editor uses the same
 * layout).
 */
public record CompanionHudPlacement(@Nonnull Point point, int x, int y) {
    /** The canvas height every client uses. */
    public static final int CANVAS_HEIGHT = 1080;
    /** The widest canvas the server clamps against (16:9). Narrower screens clamp through the lane. */
    public static final int DESIGN_WIDTH = 1920;
    /** The companion HUD plate width. */
    public static final int HUD_WIDTH = 344;
    /** The placement used when a player has never moved the HUD. */
    public static final CompanionHudPlacement DEFAULT = new CompanionHudPlacement(Point.TOP_RIGHT, -26, 218);

    private static final int EDGE_MARGIN = 26;

    public CompanionHudPlacement {
        point = point == null ? Point.TOP_RIGHT : point;
        int horizontalRoom = DESIGN_WIDTH - HUD_WIDTH;
        x = switch (point.column()) {
            case 0 -> clamp(x, 0, horizontalRoom);
            case 2 -> clamp(x, -horizontalRoom, 0);
            default -> clamp(x, -horizontalRoom / 2, horizontalRoom / 2);
        };
        y = switch (point.row()) {
            case 0 -> clamp(y, 0, CANVAS_HEIGHT);
            case 2 -> clamp(y, -CANVAS_HEIGHT, 0);
            default -> clamp(y, -CANVAS_HEIGHT / 2, CANVAS_HEIGHT / 2);
        };
    }

    /** The placement for a snap point: near that edge or corner, with the default for the top-right corner. */
    @Nonnull
    public static CompanionHudPlacement snap(@Nonnull Point point) {
        if (point == DEFAULT.point()) {
            return DEFAULT;
        }
        int x = switch (point.column()) {
            case 0 -> EDGE_MARGIN;
            case 2 -> -EDGE_MARGIN;
            default -> 0;
        };
        int y = switch (point) {
            case TOP -> 100; // below the compass
            case BOTTOM -> -150; // above the hotbar and vitals
            default -> switch (point.row()) {
                case 0 -> EDGE_MARGIN;
                case 2 -> -EDGE_MARGIN;
                default -> 0;
            };
        };
        return new CompanionHudPlacement(point, x, y);
    }

    @Nonnull
    public CompanionHudPlacement moved(int dx, int dy) {
        return new CompanionHudPlacement(point, x + dx, y + dy);
    }

    /** This placement with its vertical offset limited to where a plate of this height still fits on screen. */
    @Nonnull
    public CompanionHudPlacement fitted(int height) {
        int room = Math.max(0, CANVAS_HEIGHT - Math.max(1, height));
        int fittedY = switch (point.row()) {
            case 0 -> Math.min(y, room);
            case 2 -> Math.max(y, -room);
            default -> clamp(y, -room / 2, room / 2);
        };
        return fittedY == y ? this : new CompanionHudPlacement(point, x, fittedY);
    }

    /** The full-height lane that keeps the plate on screen. */
    @Nonnull
    public Anchor laneAnchor() {
        Anchor anchor = new Anchor();
        switch (point.column()) {
            case 0 -> {
                anchor.setLeft(Value.of(0));
                anchor.setMaxWidth(Value.of(Math.max(0, x) + HUD_WIDTH));
            }
            case 2 -> {
                anchor.setRight(Value.of(0));
                anchor.setMaxWidth(Value.of(Math.max(0, -x) + HUD_WIDTH));
            }
            // A MaxWidth-only box centres itself.
            default -> anchor.setMaxWidth(Value.of(2 * Math.abs(x) + HUD_WIDTH));
        }
        anchor.setTop(Value.of(0));
        anchor.setBottom(Value.of(0));
        return anchor;
    }

    /**
     * The plate inside its lane. The plate uses {@code MaxWidth} rather than {@code Width} so a half-pixel lane edge
     * cannot push it one pixel past the screen edge.
     */
    @Nonnull
    public Anchor plateAnchor(int height) {
        int plateHeight = Math.max(1, height);
        Anchor anchor = new Anchor();
        boolean againstRight = switch (point.column()) {
            case 0 -> true;
            case 2 -> false;
            default -> x >= 0;
        };
        if (againstRight) {
            anchor.setRight(Value.of(0));
        } else {
            anchor.setLeft(Value.of(0));
        }
        int room = Math.max(0, CANVAS_HEIGHT - plateHeight);
        switch (point.row()) {
            case 0 -> anchor.setTop(Value.of(Math.min(y, room)));
            case 2 -> anchor.setBottom(Value.of(Math.min(-y, room)));
            default -> anchor.setTop(Value.of(clamp(room / 2 + y, 0, room)));
        }
        anchor.setMaxWidth(Value.of(HUD_WIDTH));
        anchor.setHeight(Value.of(plateHeight));
        return anchor;
    }

    /** True when the plate's centre is right of the screen's centre line on a 16:9 screen. */
    public boolean rightOfCentre() {
        return switch (point.column()) {
            case 0 -> x + HUD_WIDTH / 2 > DESIGN_WIDTH / 2;
            case 2 -> DESIGN_WIDTH + x - HUD_WIDTH / 2 > DESIGN_WIDTH / 2;
            default -> x > 0;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The nine snap points, in reading order. */
    public enum Point {
        TOP_LEFT(0, 0), TOP(1, 0), TOP_RIGHT(2, 0),
        LEFT(0, 1), CENTER(1, 1), RIGHT(2, 1),
        BOTTOM_LEFT(0, 2), BOTTOM(1, 2), BOTTOM_RIGHT(2, 2);

        private final int column;
        private final int row;

        Point(int column, int row) {
            this.column = column;
            this.row = row;
        }

        public int column() {
            return column;
        }

        public int row() {
            return row;
        }

        /** The language key suffix, such as {@code topLeft}. */
        @Nonnull
        public String key() {
            String[] words = name().toLowerCase(Locale.ROOT).split("_");
            StringBuilder key = new StringBuilder(words[0]);
            for (int index = 1; index < words.length; index++) {
                key.append(Character.toUpperCase(words[index].charAt(0))).append(words[index].substring(1));
            }
            return key.toString();
        }

        /** Parses a saved point name; unknown or missing names give {@code null}. */
        @Nullable
        public static Point parse(@Nullable String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }
}

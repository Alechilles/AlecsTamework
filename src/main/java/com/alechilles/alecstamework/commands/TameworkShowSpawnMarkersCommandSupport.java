package com.alechilles.alecstamework.commands;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

final class TameworkShowSpawnMarkersCommandSupport {
    static final double DEFAULT_RADIUS = 64.0;
    static final double MIN_RADIUS = 1.0;
    static final double MAX_RADIUS = 256.0;
    private static final DebugStyle DEBUG_STYLE = new DebugStyle(
            1.0F,
            0.05F,
            0.85F,
            0.75,
            2.5,
            0.75,
            0.95F,
            1.2,
            0.35,
            1.2,
            0.95F
    );

    private TameworkShowSpawnMarkersCommandSupport() {
    }

    static ParseResult parse(String input) {
        String arg = getArg(input, 2);
        if (arg == null || arg.isBlank()) {
            return new ParseResult(Mode.SHOW, DEFAULT_RADIUS);
        }
        String normalized = arg.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(normalized) || "false".equals(normalized) || "0".equals(normalized)) {
            return new ParseResult(Mode.OFF, DEFAULT_RADIUS);
        }
        if ("on".equals(normalized) || "true".equals(normalized)) {
            return new ParseResult(Mode.SHOW, DEFAULT_RADIUS);
        }
        try {
            double parsed = Double.parseDouble(normalized);
            if (!Double.isFinite(parsed) || parsed <= 0.0) {
                return new ParseResult(Mode.INVALID, DEFAULT_RADIUS);
            }
            return new ParseResult(Mode.SHOW, clamp(parsed, MIN_RADIUS, MAX_RADIUS));
        } catch (NumberFormatException ignored) {
            return new ParseResult(Mode.INVALID, DEFAULT_RADIUS);
        }
    }

    static DebugStyle debugStyle() {
        return DEBUG_STYLE;
    }

    static String formatNpcSummary(List<String> npcIds, int maxEntries) {
        if (npcIds == null || npcIds.isEmpty() || maxEntries <= 0) {
            return "unknown";
        }

        int count = Math.min(maxEntries, npcIds.size());
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < count; i++) {
            String npcId = npcIds.get(i);
            if (npcId == null || npcId.isBlank()) {
                continue;
            }
            joiner.add(npcId);
        }

        String summary = joiner.toString();
        if (summary.isBlank()) {
            return "unknown";
        }
        int remaining = npcIds.size() - count;
        if (remaining > 0) {
            return summary + ", +" + remaining + " more";
        }
        return summary;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String getArg(String input, int index) {
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= index) {
            return null;
        }
        return tokens[index];
    }

    enum Mode {
        SHOW,
        OFF,
        INVALID
    }

    record ParseResult(Mode mode, double radius) {
    }

    record DebugStyle(float red,
                      float green,
                      float blue,
                      double markerWidth,
                      double markerHeight,
                      double markerDepth,
                      float markerAlpha,
                      double capWidth,
                      double capHeight,
                      double capDepth,
                      float capAlpha) {
    }
}

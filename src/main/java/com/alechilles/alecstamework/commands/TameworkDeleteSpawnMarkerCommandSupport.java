package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.math.vector.Vector3d;
import java.util.Locale;

final class TameworkDeleteSpawnMarkerCommandSupport {
    static final double DEFAULT_RANGE = 10.0;
    static final double MIN_RANGE = 1.0;
    static final double MAX_RANGE = 64.0;
    private static final double MIN_FORWARD_DISTANCE = 0.25;
    private static final double MIN_RAY_RADIUS = 0.75;
    private static final double MAX_RAY_RADIUS = 2.0;

    private TameworkDeleteSpawnMarkerCommandSupport() {
    }

    static ParseResult parse(String input) {
        String arg = getArg(input, 2);
        if (arg == null || arg.isBlank()) {
            return new ParseResult(Mode.DELETE, DEFAULT_RANGE);
        }
        String normalized = arg.trim().toLowerCase(Locale.ROOT);
        try {
            double parsed = Double.parseDouble(normalized);
            if (!Double.isFinite(parsed) || parsed <= 0.0) {
                return new ParseResult(Mode.INVALID, DEFAULT_RANGE);
            }
            return new ParseResult(Mode.DELETE, clamp(parsed, MIN_RANGE, MAX_RANGE));
        } catch (NumberFormatException ignored) {
            return new ParseResult(Mode.INVALID, DEFAULT_RANGE);
        }
    }

    static TargetScore scoreCandidate(Vector3d origin, Vector3d forward, Vector3d markerPosition, double range) {
        if (origin == null || forward == null || markerPosition == null || !Double.isFinite(range) || range <= 0.0) {
            return null;
        }

        double forwardLength = length(forward.x, forward.y, forward.z);
        if (!(forwardLength > 0.0001)) {
            return null;
        }

        double fx = forward.x / forwardLength;
        double fy = forward.y / forwardLength;
        double fz = forward.z / forwardLength;
        double tx = markerPosition.x - origin.x;
        double ty = markerPosition.y - origin.y;
        double tz = markerPosition.z - origin.z;
        double forwardDistance = (tx * fx) + (ty * fy) + (tz * fz);
        if (forwardDistance < MIN_FORWARD_DISTANCE || forwardDistance > range) {
            return TargetScore.noMatch(forwardDistance, Double.NaN);
        }

        double distanceSq = (tx * tx) + (ty * ty) + (tz * tz);
        double perpendicularSq = Math.max(0.0, distanceSq - (forwardDistance * forwardDistance));
        double perpendicularDistance = Math.sqrt(perpendicularSq);
        double allowedRadius = clamp(0.6 + (forwardDistance * 0.2), MIN_RAY_RADIUS, MAX_RAY_RADIUS);
        if (perpendicularDistance > allowedRadius) {
            return TargetScore.noMatch(forwardDistance, perpendicularDistance);
        }

        double normalizedOffset = perpendicularDistance / allowedRadius;
        double normalizedDistance = forwardDistance / Math.max(range, 0.0001);
        double ranking = normalizedOffset + (normalizedDistance * 0.25);
        return new TargetScore(true, ranking, forwardDistance, perpendicularDistance);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt((x * x) + (y * y) + (z * z));
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
        DELETE,
        INVALID
    }

    record ParseResult(Mode mode, double range) {
    }

    record TargetScore(boolean matches,
                       double ranking,
                       double forwardDistance,
                       double perpendicularDistance) {
        static TargetScore noMatch(double forwardDistance, double perpendicularDistance) {
            return new TargetScore(false, Double.POSITIVE_INFINITY, forwardDistance, perpendicularDistance);
        }

        boolean isBetterThan(TargetScore other) {
            if (!matches) {
                return false;
            }
            if (other == null || !other.matches) {
                return true;
            }
            return ranking < other.ranking;
        }
    }
}

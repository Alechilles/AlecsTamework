package com.alechilles.alecstamework.npc.progression;

import javax.annotation.Nullable;

/** Shared formatting and stack-count math for Tamework tranquilizer presentation. */
public final class TranquilizerStackDisplayService {
    public static final double STACK_DURATION_SECONDS = 30.0;

    private TranquilizerStackDisplayService() {
    }

    public static int computeStacks(double initialDurationSeconds) {
        if (!Double.isFinite(initialDurationSeconds) || initialDurationSeconds <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(initialDurationSeconds / STACK_DURATION_SECONDS));
    }

    public static double resolvePeakDuration(double trackedPeakSeconds, double currentRemainingSeconds) {
        double tracked = sanitizePositive(trackedPeakSeconds);
        double current = sanitizePositive(currentRemainingSeconds);
        return Math.max(tracked, current);
    }

    public static String formatRemainingDuration(double remainingSeconds) {
        long totalSeconds = Math.max(0L, (long) Math.ceil(remainingSeconds));
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0L) {
            return totalSeconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }

    @Nullable
    public static String formatStackValue(int stacks, @Nullable String remainingText, int variantIndex) {
        return switch (variantIndex) {
            case 1 -> stacks > 0 ? Integer.toString(stacks) : null;
            case 2 -> remainingText;
            default -> {
                if (stacks <= 0) {
                    yield remainingText;
                }
                if (remainingText == null || remainingText.isBlank()) {
                    yield Integer.toString(stacks);
                }
                yield stacks + " (" + remainingText + ")";
            }
        };
    }

    private static double sanitizePositive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return value;
    }
}

package com.alechilles.alecstamework.npc.progression;

/**
 * Identifies historical Unix wall-clock timestamps stored in signed world-timeline fields.
 *
 * <p>Old saves carry no clock provenance, so the year-2000 boundary separates modern Unix values
 * from normal Hytale year-0001 world values while preserving small positive cross-zero deadlines.
 */
final class LegacyWorldTimestampClassifier {
    private static final long WALL_CLOCK_EPOCH_FLOOR_MS = 946_684_800_000L;

    private LegacyWorldTimestampClassifier() {
    }

    static boolean shouldTranslate(long candidateTimestampMs, long currentWorldTimestampMs) {
        return currentWorldTimestampMs < WALL_CLOCK_EPOCH_FLOOR_MS
                && candidateTimestampMs >= WALL_CLOCK_EPOCH_FLOOR_MS;
    }
}

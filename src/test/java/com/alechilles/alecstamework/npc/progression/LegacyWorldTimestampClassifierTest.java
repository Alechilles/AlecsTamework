package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for legacy wall-clock versus signed-world timestamp classification. */
class LegacyWorldTimestampClassifierTest {
    @Test
    void preservesPositiveWorldDeadlineCrossingEpochZero() {
        assertFalse(LegacyWorldTimestampClassifier.shouldTranslate(500L, -1_000L));
    }

    @Test
    void translatesModernUnixValueOnlyOutsideWallClockTimelineDomain() {
        long unixTimestamp = 1_800_000_000_000L;

        assertTrue(LegacyWorldTimestampClassifier.shouldTranslate(unixTimestamp, -1_000L));
        assertTrue(LegacyWorldTimestampClassifier.shouldTranslate(unixTimestamp, 1_000L));
        assertFalse(LegacyWorldTimestampClassifier.shouldTranslate(unixTimestamp, unixTimestamp));
    }
}

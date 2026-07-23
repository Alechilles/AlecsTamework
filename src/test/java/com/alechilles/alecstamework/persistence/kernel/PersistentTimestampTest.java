package com.alechilles.alecstamework.persistence.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for signed world timestamps and their sole unset sentinel. */
class PersistentTimestampTest {
    @Test
    void zeroAloneIsUnset() {
        assertSame(PersistentTimestamp.UNSET, PersistentTimestamp.of(0));
        assertTrue(PersistentTimestamp.of(0).isUnset());
        assertFalse(PersistentTimestamp.of(0).isSet());
        assertTrue(PersistentTimestamp.of(-1).isSet());
        assertTrue(PersistentTimestamp.of(1).isSet());
    }

    @Test
    void negativeTimestampPreservesSignAndOrdering() {
        PersistentTimestamp earlier = PersistentTimestamp.of(-2_000);
        PersistentTimestamp later = PersistentTimestamp.of(-1_000);

        assertEquals(-2_000, earlier.epochMillis());
        assertTrue(earlier.compareTo(later) < 0);
        assertTrue(later.compareTo(earlier) > 0);
    }

    @Test
    void negativeDeadlineUsesOrderingInsteadOfPositiveChecks() {
        PersistentTimestamp deadline = PersistentTimestamp.of(-1_000);

        assertTrue(deadline.isPendingAt(-1_001));
        assertFalse(deadline.hasElapsedAt(-1_001));
        assertFalse(deadline.isPendingAt(-1_000));
        assertTrue(deadline.hasElapsedAt(-1_000));
        assertTrue(deadline.hasElapsedAt(-999));
    }

    @Test
    void unsetTimestampNeverActsAsADeadline() {
        assertFalse(PersistentTimestamp.UNSET.isPendingAt(Long.MIN_VALUE));
        assertFalse(PersistentTimestamp.UNSET.hasElapsedAt(Long.MAX_VALUE));
    }
}

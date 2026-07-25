package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Domain invariant tests for normalized timed summon lease evidence. */
class TimedSummonLeaseTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000091");

    @Test
    void signedWorldTimeIsValidAndCooldownUsesOrdering() {
        TimedSummonLease lease = dormant(-2_000L);

        assertTrue(lease.cooldownActive(-3_000));
        assertFalse(lease.cooldownActive(-2_000));
        assertFalse(lease.cooldownActive(-1_000));
    }

    @Test
    void finiteAndUnlimitedSessionsUseOneConsistentShape() {
        TimedSummonLease finite = new TimedSummonLease(
                PROFILE,
                1,
                session(1),
                8_000L,
                null,
                policy(10_000),
                Set.of(5_000L),
                -2_000L,
                -4_000,
                -2_000
        );
        TimedSummonLease unlimited = new TimedSummonLease(
                PROFILE,
                1,
                session(2),
                null,
                null,
                policy(0),
                Set.of(),
                -2_000L,
                -4_000,
                -2_000
        );

        assertTrue(finite.activeSession());
        assertFalse(finite.unlimitedSession());
        assertTrue(unlimited.unlimitedSession());
    }

    @Test
    void inconsistentSessionEvidenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new TimedSummonLease(
                        PROFILE,
                        1,
                        null,
                        1_000L,
                        null,
                        policy(10_000),
                        Set.of(),
                        null,
                        0,
                        0
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new TimedSummonLease(
                        PROFILE,
                        1,
                        session(1),
                        null,
                        null,
                        policy(10_000),
                        Set.of(),
                        -1L,
                        -2_000,
                        -1_000
                )
        );
    }

    @Test
    void warningThresholdsAreUniqueDescendingPolicyEvidence() {
        assertThrows(IllegalArgumentException.class, () ->
                new TimedSummonPolicy(
                        "role:timed",
                        1L,
                        10_000,
                        2_000,
                        true,
                        List.of(1_000L, 5_000L)
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new TimedSummonPolicy(
                        "role:timed",
                        1L,
                        10_000,
                        2_000,
                        true,
                        List.of(5_000L, 5_000L)
                )
        );
    }

    @Test
    void signedCooldownAdditionAndMonotonicCountdownDoNotUseEpochTime() {
        assertEquals(-500, TimedSummonTime.saturatingAdd(-2_000, 1_500));
        assertEquals(
                Long.MAX_VALUE,
                TimedSummonTime.saturatingAdd(Long.MAX_VALUE - 5, 10)
        );
        assertEquals(
                8_500,
                TimedSummonTime.remaining(
                        10_000,
                        20_000_000_000L,
                        21_500_000_000L
                )
        );
        assertEquals(
                10_000,
                TimedSummonTime.remaining(
                        10_000,
                        30_000_000_000L,
                        30_000_000_000L
                )
        );
        assertEquals(
                9_000,
                TimedSummonTime.remaining(
                        10_000,
                        -2_000_000_000L,
                        -1_000_000_000L
                )
        );
        assertEquals(
                9_999,
                TimedSummonTime.remaining(
                        10_000,
                        Long.MAX_VALUE - 500_000L,
                        Long.MIN_VALUE + 499_999L
                )
        );
    }

    private TimedSummonLease dormant(Long cooldownUntilMs) {
        return new TimedSummonLease(
                PROFILE,
                1,
                null,
                null,
                cooldownUntilMs,
                policy(10_000),
                Set.of(),
                null,
                -5_000,
                -3_000
        );
    }

    private TimedSummonPolicy policy(long durationMs) {
        return new TimedSummonPolicy(
                "role:timed",
                1L,
                durationMs,
                2_000,
                true,
                durationMs == 0
                        ? List.of()
                        : List.of(5_000L, 1_000L)
        );
    }

    private TimedSummonSessionId session(long value) {
        return new TimedSummonSessionId(new UUID(0, value));
    }
}


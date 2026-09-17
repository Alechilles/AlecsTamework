package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies durable owner-time accounting without loaded world or chunk access. */
class AnimalProgressionClockTest {
    private static final long HOUR_MS = 3_600_000L;

    @TempDir Path tempDir;

    private final AnimalProgressionClock clock = AnimalProgressionClock.get();

    @BeforeEach
    void setUp() {
        clock.clearForTests();
        clock.start(tempDir);
    }

    @AfterEach
    void tearDown() {
        clock.clearForTests();
    }

    @Test
    void repeatedLoginsAccumulateOnlyConnectedTime() {
        UUID ownerId = UUID.randomUUID();
        clock.onOwnerConnected(ownerId);
        advance(60_000L);
        clock.onOwnerDisconnected(ownerId);
        advance(30_000L);
        clock.onOwnerConnected(ownerId);
        advance(15_000L);

        assertEquals(75_000L, clock.current(ownerId));
    }

    @Test
    void restartRetainsCounterButExcludesServerDowntime() {
        UUID ownerId = UUID.randomUUID();
        clock.onOwnerConnected(ownerId);
        advance(60_000L);
        clock.onOwnerDisconnected(ownerId);
        clock.close();

        CompanionRuntimeClock.resetForTests();
        clock.start(tempDir);
        advance(2L * HOUR_MS);

        assertEquals(60_000L, clock.current(ownerId));
    }

    @Test
    void restartPreservesAlreadyConsumedOfflineGraceWithoutCountingDowntime() {
        UUID ownerId = UUID.randomUUID();
        clock.onPolicyChanged(TwNeedsConfig.TickPolicySettings.of(
                TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY, 1.0, 0.5
        ));
        clock.onOwnerConnected(ownerId);
        clock.onOwnerDisconnected(ownerId);
        advance(30L * 60_000L);
        clock.close();

        CompanionRuntimeClock.resetForTests();
        clock.start(tempDir);
        clock.onPolicyChanged(TwNeedsConfig.TickPolicySettings.of(
                TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY, 1.0, 0.5
        ));
        advance(30L * 60_000L);
        assertEquals(0L, clock.current(ownerId));

        advance(60L * 60_000L);
        assertEquals(30L * 60_000L, clock.current(ownerId));
    }

    @Test
    void offlineGraceThenRateAccruesOnlyTheEligibleSegment() {
        UUID ownerId = UUID.randomUUID();
        clock.onPolicyChanged(TwNeedsConfig.TickPolicySettings.of(
                TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY, 1.0, 0.5
        ));
        clock.onOwnerConnected(ownerId);
        clock.onOwnerDisconnected(ownerId);
        advance(HOUR_MS);

        assertEquals(0L, clock.current(ownerId));

        advance(HOUR_MS);

        assertEquals(HOUR_MS / 2L, clock.current(ownerId));
    }

    @Test
    void unknownOwnerIsNotCreditedForEarlierRuntime() {
        advance(2L * HOUR_MS);

        assertEquals(0L, clock.current(UUID.randomUUID()));
    }

    @Test
    void nullOwnerUsesTheServerRuntimeClock() {
        advance(12_000L);

        assertEquals(12_000L, clock.current(null));
    }

    private static void advance(long millis) {
        CompanionRuntimeClock.advanceByDeltaSeconds(millis / 1_000.0f);
    }
}

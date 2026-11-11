package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.alechilles.alecstamework.access.SharedAccessService;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies whose presence makes a shared or private companion's needs run. */
class SharedPresenceServiceTest {
    private static final long HOUR_MS = 3_600_000L;
    private static final TwNeedsConfig.TickPolicySettings POLICY = TwNeedsConfig.TickPolicySettings.of(
            TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY, 72.0, 1.0);

    private final SharedAccessService access = SharedAccessService.get();
    private final SharedPresenceService presence = SharedPresenceService.get();
    private final OwnerPresenceTimelineService timeline = OwnerPresenceTimelineService.get();
    private final UUID owner = UUID.randomUUID();
    private final UUID sharer = UUID.randomUUID();
    private final UUID sharedCompanion = UUID.randomUUID();
    private final UUID privateCompanion = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access.clearForTests();
        presence.clearForTests();
        timeline.clearForTests();
        access.grantPlayer(owner, sharer, "Sharer", AccessLevel.CARETAKER);
        access.setPrivate(owner, privateCompanion, true);
    }

    @AfterEach
    void tearDown() {
        access.clearForTests();
        presence.clearForTests();
        timeline.clearForTests();
    }

    @Test
    void sharedCompanionNeedsRunWhileOnlyASharerIsOnline() {
        presence.onPlayerOnline(sharer);
        presence.sweep();
        UUID sharedId = presence.presenceIdFor(owner, sharedCompanion);
        CompanionRuntimeClock.advanceByDeltaSeconds(3_600.0f);

        assertNotEquals(owner, sharedId);
        assertEquals(HOUR_MS, timeline.resolveEffectiveElapsedMs(sharedId, 0L, HOUR_MS, POLICY));
    }

    @Test
    void privateCompanionFollowsItsOwnerEvenWhileASharerIsOnline() {
        timeline.markOfflineForTests(owner, 0L);
        presence.onPlayerOnline(sharer);
        presence.sweep();
        UUID privateId = presence.presenceIdFor(owner, privateCompanion);
        CompanionRuntimeClock.advanceByDeltaSeconds(3_600.0f);

        assertEquals(owner, privateId);
        // The owner is offline and inside the grace, so nothing drains.
        assertEquals(0L, timeline.resolveEffectiveElapsedMs(privateId, 0L, HOUR_MS, POLICY));
    }

    @Test
    void sharedCompanionNeedsPauseOnceTheLastSharerLeaves() {
        presence.onPlayerOnline(sharer);
        presence.sweep();
        UUID sharedId = presence.presenceIdFor(owner, sharedCompanion);
        CompanionRuntimeClock.advanceByDeltaSeconds(3_600.0f);
        presence.onPlayerOffline(sharer);
        presence.sweep();
        CompanionRuntimeClock.advanceByDeltaSeconds(3_600.0f);

        assertEquals(0L, timeline.resolveEffectiveElapsedMs(sharedId, HOUR_MS, 2L * HOUR_MS, POLICY));
    }

    @Test
    void companionReturnsToItsOwnersPresenceWhenSharingEnds() {
        access.revokePlayer(owner, sharer);

        assertEquals(owner, presence.presenceIdFor(owner, sharedCompanion));
    }
}

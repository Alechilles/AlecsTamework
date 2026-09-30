package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionFenceTest {
    private static CompanionRecord record(CompanionLocation location, long generation) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", location).ownerUuid(UUID.randomUUID()).generation(generation).build();
    }

    private static final CompanionRecord LIVE_G3 = record(CompanionLocation.live("default", 0, 64, 0), 3);

    @Test
    void migratedStaleBodyIsRemoved() {
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(LIVE_G3, false, CompanionFence.STALE_MIGRATED_GENERATION, true, false));
    }

    @Test
    void releasedTombstoneRemovesTheBody() {
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(record(CompanionLocation.released("owner_release"), 3), false, 3, true, false));
    }

    @Test
    void unreadableRecordLeavesTheBodyAlone() {
        assertEquals(FenceAction.IGNORE, CompanionFence.decide(null, true, 3, true, false));
    }

    @Test
    void missingRecordAdoptsAnOwnedTamedBodyAndRemovesAnythingElse() {
        assertEquals(FenceAction.ADOPT, CompanionFence.decide(null, false, 0, true, false));
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(null, false, 0, false, false));
    }

    @Test
    void matchingLiveBodyIsAcceptedUnlessAnotherBodyIsLoaded() {
        assertEquals(FenceAction.ACCEPT, CompanionFence.decide(LIVE_G3, false, 3, true, false));
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(LIVE_G3, false, 3, true, true));
    }

    @Test
    void bodyNewerThanItsRecordIsAcceptedAndRaised() {
        assertEquals(FenceAction.ACCEPT_AND_RAISE, CompanionFence.decide(LIVE_G3, false, 4, true, false));
    }

    @Test
    void olderBodyOrARecordThatIsNotLiveRemovesTheBody() {
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(LIVE_G3, false, 2, true, false));
        assertEquals(FenceAction.REMOVE, CompanionFence.decide(record(CompanionLocation.item(), 3), false, 3, true, false));
    }
}

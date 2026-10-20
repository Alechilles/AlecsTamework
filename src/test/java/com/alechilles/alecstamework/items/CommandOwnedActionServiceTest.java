package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandOwnedActionServiceTest {
    /** Index records open actions only to their owner, and locate also reaches captured and cooped ones. */
    @Test void indexRecordsGateOnTheirOwnerAndWhereTheCompanionIs() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        for (var location : List.of(CompanionLocation.live("default", 0, 0, 0),
                CompanionLocation.dead(null), CompanionLocation.lost(null))) {
            var companion = companion(owner, location);
            assertTrue(CommandOwnedActionService.allows(owner, companion));
            assertFalse(CommandOwnedActionService.allows(stranger, companion));
        }
        for (var location : List.of(CompanionLocation.item(), CompanionLocation.coop("default", 1, 2, 3, 0))) {
            var companion = companion(owner, location);
            assertFalse(CommandOwnedActionService.allows(owner, companion));
            assertTrue(CommandOwnedActionService.allowsLocate(owner, companion));
            assertFalse(CommandOwnedActionService.allowsLocate(stranger, companion));
        }
        for (var location : List.of(CompanionLocation.stored(StoredReason.ROSTER), CompanionLocation.released(null))) {
            assertFalse(CommandOwnedActionService.allowsLocate(owner, companion(owner, location)));
        }
        assertFalse(CommandOwnedActionService.allows(owner, (CompanionRecord) null));
    }

    /** Recover reaches an owned companion in a capture item or a coop (spec 8.14) but not a stranger's. */
    @Test void recoverAlsoReachesAnOwnedCompanionInACaptureItemOrCoop() {
        UUID owner = UUID.randomUUID();
        var item = companion(owner, CompanionLocation.item());
        var coop = companion(owner, CompanionLocation.coop("default", 1, 2, 3, 0));
        assertTrue(CommandOwnedActionService.allowsRecover(owner, item));
        assertFalse(CommandOwnedActionService.allowsRecover(UUID.randomUUID(), item));
        assertTrue(CommandOwnedActionService.allowsRecover(owner, coop));
        assertFalse(CommandOwnedActionService.allowsRecover(UUID.randomUUID(), coop));
        assertFalse(CommandOwnedActionService.allows(owner, coop));
        assertTrue(CommandOwnedActionService.allowsRecover(owner, companion(owner, CompanionLocation.lost(null))));
    }

    /** A crafted generic-item action must not reach a command-family roster member; bonded records keep their gate. */
    @Test void indexRosterMembersAreNotActionableFromGenericItems() {
        UUID owner = UUID.randomUUID();
        var live = CompanionLocation.live("default", 0, 0, 0);
        var member = companion(owner, live).toBuilder().rosterId("dragons").build();
        var bonded = companion(owner, live).toBuilder().rosterId("dragons").bonded(true).build();

        assertFalse(CommandOwnedActionService.allows(owner, member));
        assertFalse(CommandOwnedActionService.allowsLocate(owner, member));
        assertTrue(CommandOwnedActionService.allows(owner, bonded));
    }

    private static CompanionRecord companion(UUID owner, CompanionLocation location) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", location).ownerUuid(owner).build();
    }

    /** Without the LIVE position a recall of an unloaded owned companion has no source chunk to load. */
    @Test void indexActionRecordCarriesTheLivePosition() {
        var record = CommandOwnedActionService.record(
                companion(UUID.randomUUID(), CompanionLocation.live("default", 10, 64, -20)), UUID.randomUUID());
        assertEquals(10, record.lastKnownPosition.x);
        assertEquals(-20, record.lastKnownPosition.z);
    }
}

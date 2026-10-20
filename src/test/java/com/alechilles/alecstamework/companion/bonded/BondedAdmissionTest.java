package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BondedAdmissionTest {
    private static final String ROSTER = "hydragon:horn";
    private static final String DRAGON = "Tamed_Dragon_Fire";
    private static final String MINI = "Bonded_Miniwyvern";
    private final UUID owner = UUID.randomUUID();

    private static BondedCompanionPolicy family(String familyId, String role, int maximumOwned, int maximumActive) {
        return new BondedCompanionPolicy(1L, ROSTER, familyId, Set.of(role), maximumOwned, maximumActive, 0L, 0L,
                null, new BondedCompanionPolicy.FeatureFlags(true, true, true, true, true));
    }

    /** Two families in one roster: dragons with the given caps, and miniwyverns capped at one of each. */
    private static BondedRecords.Families families(int dragonsOwned, int dragonsActive) {
        BondedCompanionPolicy dragons = family("hydragon:dragon", DRAGON, dragonsOwned, dragonsActive);
        BondedCompanionPolicy minis = family("hydragon:miniwyvern", MINI, 1, 1);
        return (rosterId, roleId) -> !ROSTER.equals(rosterId) ? null
                : DRAGON.equals(roleId) ? dragons : MINI.equals(roleId) ? minis : null;
    }

    private CompanionRecord rec(String role, CompanionLocation at) {
        return CompanionRecord.builder(UUID.randomUUID(), role, at).ownerUuid(owner).rosterId(ROSTER).bonded(true).build();
    }

    private static CompanionLocation live() {
        return CompanionLocation.live("w", 0, 0, 0);
    }

    private static CompanionLocation stored() {
        return CompanionLocation.stored(StoredReason.BONDED);
    }

    @Test
    void aNewCompanionPastTheFamilyOwnedLimitIsRefused() {
        List<CompanionRecord> mine = List.of(rec(DRAGON, stored()), rec(DRAGON, CompanionLocation.dead(null)));

        assertEquals(BondedAdmission.Refusal.OWNED_CAPACITY,
                BondedAdmission.check(mine, null, rec(DRAGON, stored()), families(2, 0)));
        assertNull(BondedAdmission.check(mine, null, rec(DRAGON, stored()), families(3, 0)));
    }

    @Test
    void aReleasedTombstoneDoesNotCountAsOwned() {
        List<CompanionRecord> mine = List.of(rec(DRAGON, CompanionLocation.released(null)));

        assertNull(BondedAdmission.check(mine, null, rec(DRAGON, stored()), families(1, 0)));
    }

    @Test
    void aSummonPastTheFamilyActiveLimitIsRefused() {
        CompanionRecord stored = rec(DRAGON, stored());
        List<CompanionRecord> mine = List.of(stored, rec(DRAGON, live()));
        CompanionRecord summoned = stored.toBuilder().location(live()).build();

        assertEquals(BondedAdmission.Refusal.ACTIVE_CAPACITY, BondedAdmission.check(mine, stored, summoned, families(0, 1)));
        assertNull(BondedAdmission.check(mine, stored, summoned, families(0, 2)));
    }

    @Test
    void aMoveThatAddsNothingPassesEvenOverALoweredLimit() {
        CompanionRecord live = rec(DRAGON, live());
        List<CompanionRecord> mine = List.of(live, rec(DRAGON, live()), rec(DRAGON, stored()));

        assertNull(BondedAdmission.check(mine, live, live.toBuilder().location(stored()).build(), families(1, 1)));
        assertNull(BondedAdmission.check(mine, live, live.toBuilder().location(CompanionLocation.dead(null)).build(),
                families(1, 1)));
        assertNull(BondedAdmission.check(mine, live, live.toBuilder().location(CompanionLocation.live("w", 5, 5, 5)).build(),
                families(1, 1)), "a position refresh of an active companion adds nothing");
    }

    @Test
    void zeroMeansNoLimit() {
        List<CompanionRecord> mine = List.of(rec(DRAGON, live()), rec(DRAGON, live()), rec(DRAGON, live()));

        assertNull(BondedAdmission.check(mine, null, rec(DRAGON, live()), families(0, 0)));
    }

    @Test
    void anotherFamilyOfTheSameRosterDoesNotCount() {
        List<CompanionRecord> mine = List.of(rec(MINI, live()));

        assertNull(BondedAdmission.check(mine, null, rec(DRAGON, live()), families(1, 1)));
        assertEquals(BondedAdmission.Refusal.OWNED_CAPACITY,
                BondedAdmission.check(mine, null, rec(MINI, stored()), families(1, 1)));
    }

    @Test
    void unbondedRecordsOfAnAllowedRoleDoNotCount() {
        CompanionRecord ordinary = CompanionRecord.builder(UUID.randomUUID(), DRAGON, live()).ownerUuid(owner).build();

        assertNull(BondedAdmission.check(List.of(ordinary), null, rec(DRAGON, live()), families(1, 1)));
        assertNull(BondedAdmission.check(List.of(rec(DRAGON, live())), null, ordinary, families(1, 1)));
    }
}

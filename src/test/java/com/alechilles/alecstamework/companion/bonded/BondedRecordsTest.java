package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionPresentationAttributes;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BondedRecordsTest {
    private static final String ROSTER = "hydragon:horn";
    private static final String DRAGON = "Tamed_Dragon_Fire";
    private static final String BOTH = "Role_In_Two_Families";
    private static final BondedCompanionPolicy.FeatureFlags ALL =
            new BondedCompanionPolicy.FeatureFlags(true, true, true, true, true);
    private final UUID owner = UUID.randomUUID();

    private static BondedCompanionPolicy dragons(int maximumActive, BondedCompanionPolicy.FeatureFlags features) {
        return new BondedCompanionPolicy(7L, ROSTER, "hydragon:fire_dragon", Set.of(DRAGON, BOTH), 0, maximumActive,
                600L, 30L, 120L, null, null,
                new BondedCompanionPolicy.RevivePrice(List.of(new BondedCompanionReviveCost("Gem", 3))), features);
    }

    /** One family per roster, except {@link #BOTH}, which two families allow. */
    private static BondedRecords.Families families(BondedCompanionPolicy policy) {
        return (rosterId, roleId) -> ROSTER.equals(rosterId) && DRAGON.equals(roleId) ? policy : null;
    }

    private CompanionRecord.Builder bonded(String role, CompanionLocation at) {
        return CompanionRecord.builder(UUID.randomUUID(), role, at).ownerUuid(owner).rosterId(ROSTER).bonded(true)
                .generation(4).revision(90).displayName("Ember");
    }

    private BondedCompanionProfileView view(CompanionRecord record, List<CompanionRecord> mine,
                                            BondedCompanionPolicy policy, long nowMs) {
        return BondedRecords.view(record, mine, families(policy), nowMs, Map.of());
    }

    @Test
    void aStoredCompanionIsSummonableOncePastItsCooldown() {
        CompanionRecord stored = bonded(DRAGON, CompanionLocation.stored(StoredReason.BONDED))
                .summonCooldownUntilMs(5_000L).build();

        BondedCompanionProfileView waiting = view(stored, List.of(stored), dragons(0, ALL), 4_999L);
        BondedCompanionProfileView ready = view(stored, List.of(stored), dragons(0, ALL), 5_000L);

        assertEquals(BondedCompanionStateView.STORED, waiting.state());
        assertEquals(stored.profileId().toString(), waiting.profileId());
        assertEquals("hydragon:fire_dragon", waiting.familyId());
        assertEquals(4L, waiting.revision(), "the fence is the generation, not the record revision");
        assertEquals(5_000L, waiting.summonCooldownUntilMs());
        assertNull(waiting.activeLease());
        assertFalse(waiting.summonAvailable());
        assertTrue(ready.summonAvailable());
        assertFalse(ready.storeAvailable());
        assertFalse(ready.reviveAvailable());
        assertNull(ready.reviveQuote());
    }

    @Test
    void aLostBodyIsListedAsStored() {
        CompanionRecord lost = bonded(DRAGON, CompanionLocation.lost("WORLD_REMOVED")).build();

        BondedCompanionProfileView view = view(lost, List.of(lost), dragons(0, ALL), 0L);

        assertEquals(BondedCompanionStateView.STORED, view.state());
        assertTrue(view.summonAvailable());
        assertNull(view.reviveQuote());
    }

    @Test
    void anActiveCompanionCarriesATimedLease() {
        UUID npc = UUID.randomUUID();
        CompanionRecord live = bonded(DRAGON, CompanionLocation.live("world-a", 1, 2, 3)).currentNpcUuid(npc)
                .summonedUntilMs(1_000_000L).updatedAtMs(999_000L).build();

        BondedCompanionProfileView view = view(live, List.of(live), dragons(1, ALL), 500_000L);

        assertEquals(BondedCompanionStateView.ACTIVE, view.state());
        assertTrue(view.storeAvailable());
        assertFalse(view.summonAvailable());
        assertEquals(new BondedCompanionLeaseView("4", npc, "world-a", 400_000L, 1_000_000L), view.activeLease());
    }

    @Test
    void anUnlimitedLeaseStartsWhenTheRecordWasLastUpdated() {
        CompanionRecord live = bonded(DRAGON, CompanionLocation.live("world-a", 0, 0, 0)).updatedAtMs(42_000L).build();

        BondedCompanionLeaseView lease = BondedRecords.lease(live, 600_000L);

        assertTrue(lease.unlimited());
        assertEquals(42_000L, lease.startedAtMs());
    }

    @Test
    void aDeadCompanionIsRevivableOncePastItsCooldownAndCarriesItsPrice() {
        CompanionRecord dead = bonded(DRAGON, CompanionLocation.dead("PLAYER")).diedAtMs(1_000L)
                .reviveAvailableAtMs(121_000L).build();

        BondedCompanionProfileView waiting = view(dead, List.of(dead), dragons(0, ALL), 119_500L);
        BondedCompanionProfileView ready = view(dead, List.of(dead), dragons(0, ALL), 121_000L);

        assertEquals(BondedCompanionStateView.DEAD, waiting.state());
        assertFalse(waiting.reviveAvailable());
        assertEquals(2L, waiting.reviveQuote().cooldownRemainingSeconds(), "a part second rounds up");
        assertEquals(List.of(new BondedCompanionReviveQuote.CostLine("Gem", 3, 0)), waiting.reviveQuote().costs());
        assertEquals(7L, waiting.reviveQuote().policyRevision());
        assertTrue(ready.reviveAvailable());
        assertEquals(0L, ready.reviveQuote().cooldownRemainingSeconds());
        assertFalse(ready.summonAvailable());
    }

    @Test
    void aFeatureSwitchedOffMakesItsActionUnavailable() {
        BondedCompanionPolicy off = dragons(0, new BondedCompanionPolicy.FeatureFlags(true, true, false, false, false));
        CompanionRecord stored = bonded(DRAGON, CompanionLocation.stored(StoredReason.BONDED)).build();
        CompanionRecord live = bonded(DRAGON, CompanionLocation.live("w", 0, 0, 0)).build();
        CompanionRecord dead = bonded(DRAGON, CompanionLocation.dead(null)).build();
        List<CompanionRecord> mine = List.of(stored, live, dead);

        assertFalse(view(stored, mine, off, 0L).summonAvailable());
        assertFalse(view(live, mine, off, 0L).storeAvailable());
        assertFalse(view(dead, mine, off, 0L).reviveAvailable());
        assertNull(view(dead, mine, off, 0L).reviveQuote());
    }

    @Test
    void summonIsUnavailableWhileTheFamilyIsAtItsActiveLimit() {
        CompanionRecord stored = bonded(DRAGON, CompanionLocation.stored(StoredReason.BONDED)).build();
        CompanionRecord live = bonded(DRAGON, CompanionLocation.live("w", 0, 0, 0)).build();
        CompanionRecord unbondedLive = CompanionRecord.builder(UUID.randomUUID(), DRAGON,
                CompanionLocation.live("w", 0, 0, 0)).ownerUuid(owner).build();

        BondedCompanionProfileView full = view(stored, List.of(stored, live), dragons(1, ALL), 0L);

        assertFalse(full.summonAvailable());
        assertEquals("1", full.snapshotPresentationData().get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_COUNT));
        assertEquals("1", full.snapshotPresentationData().get(BondedCompanionPresentationAttributes.ACTIVE_CAPACITY_LIMIT));
        assertTrue(view(stored, List.of(stored, live), dragons(2, ALL), 0L).summonAvailable());
        assertTrue(view(stored, List.of(stored, unbondedLive), dragons(1, ALL), 0L).summonAvailable(),
                "only bonded records of the family count");
    }

    @Test
    void aRoleWithNoSingleFamilyIsListedWithEveryActionUnavailable() {
        CompanionRecord stored = bonded(BOTH, CompanionLocation.stored(StoredReason.BONDED)).build();
        CompanionRecord live = bonded(BOTH, CompanionLocation.live("w", 0, 0, 0)).summonedUntilMs(9_000L)
                .updatedAtMs(8_000L).build();
        CompanionRecord dead = bonded(BOTH, CompanionLocation.dead(null)).build();
        List<CompanionRecord> mine = List.of(stored, live, dead);

        BondedCompanionProfileView storedView = view(stored, mine, dragons(0, ALL), 0L);
        BondedCompanionProfileView liveView = view(live, mine, dragons(0, ALL), 0L);
        BondedCompanionProfileView deadView = view(dead, mine, dragons(0, ALL), 0L);

        assertEquals(BondedRecords.UNRESOLVED_FAMILY_ID, storedView.familyId());
        assertFalse(storedView.summonAvailable());
        assertFalse(liveView.storeAvailable());
        assertNotNull(liveView.activeLease());
        assertEquals(8_000L, liveView.activeLease().startedAtMs());
        assertFalse(deadView.reviveAvailable());
        assertNull(deadView.reviveQuote());
    }

    @Test
    void recordsThatAreNotHeldBondedCompanionsAreNotListed() {
        BondedRecords.Families families = families(dragons(0, ALL));
        CompanionRecord unbonded = bonded(DRAGON, CompanionLocation.stored(StoredReason.ROSTER)).bonded(false).build();

        assertNull(BondedRecords.view(bonded(DRAGON, CompanionLocation.released(null)).build(), List.of(), families, 0L, Map.of()));
        assertNull(BondedRecords.view(bonded(DRAGON, CompanionLocation.item()).build(), List.of(), families, 0L, Map.of()));
        assertNull(BondedRecords.view(bonded(DRAGON, CompanionLocation.coop("w", 0, 0, 0, 0)).build(), List.of(), families, 0L, Map.of()));
        assertNull(BondedRecords.view(unbonded, List.of(), families, 0L, Map.of()));
    }

    @Test
    void aDeadCompanionIsNotRevivableWhileItsFamilyHasNoFreeActivePlace() {
        CompanionRecord live = bonded(DRAGON, CompanionLocation.live("world-a", 0, 0, 0)).build();
        CompanionRecord dead = bonded(DRAGON, CompanionLocation.dead("PLAYER")).build();

        assertFalse(view(dead, List.of(live, dead), dragons(1, ALL), 0L).reviveAvailable(),
                "a revived companion comes back active");
        assertTrue(view(dead, List.of(live, dead), dragons(2, ALL), 0L).reviveAvailable());
    }

    /** The panel header and the "dismiss first" sentence read these entries. */
    @Test
    void aBlockedCompanionOfAFullFamilyCarriesTheOwnedCountsAndTheActiveCompanionsName() {
        BondedCompanionPolicy limited = new BondedCompanionPolicy(7L, ROSTER, "hydragon:fire_dragon",
                Set.of(DRAGON), 5, 1, 600L, 30L, 120L, null, null, null, ALL);
        CompanionRecord live = bonded(DRAGON, CompanionLocation.live("world-a", 0, 0, 0)).displayName("Blaze").build();
        CompanionRecord stored = bonded(DRAGON, CompanionLocation.stored(StoredReason.BONDED)).build();
        List<CompanionRecord> mine = List.of(stored, live);

        Map<String, String> blocked = view(stored, mine, limited, 0L).snapshotPresentationData();
        Map<String, String> active = view(live, mine, limited, 0L).snapshotPresentationData();

        assertEquals("2", blocked.get(BondedRecords.OWNED_CAPACITY_COUNT));
        assertEquals("5", blocked.get(BondedRecords.OWNED_CAPACITY_LIMIT));
        assertEquals("Blaze", blocked.get(BondedRecords.ACTIVE_BLOCKER_NAME));
        assertEquals(DRAGON, blocked.get(BondedRecords.ACTIVE_BLOCKER_ROLE_ID));
        assertFalse(active.containsKey(BondedRecords.ACTIVE_BLOCKER_NAME), "an active companion is not blocked");
        // A family without an owned limit, or with a free active place, carries neither.
        Map<String, String> free = view(stored, List.of(stored), dragons(1, ALL), 0L).snapshotPresentationData();
        assertFalse(free.containsKey(BondedRecords.OWNED_CAPACITY_COUNT));
        assertFalse(free.containsKey(BondedRecords.ACTIVE_BLOCKER_ROLE_ID));
    }
}

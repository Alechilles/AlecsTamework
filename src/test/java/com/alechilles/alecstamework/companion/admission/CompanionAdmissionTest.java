package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompanionAdmissionTest {
    private final UUID owner = UUID.randomUUID();
    private static final PopulationGroupPolicy DRAGONS =
            new PopulationGroupPolicy("dragons", PopulationGroupScope.GLOBAL, 2, 1, 1);

    private static final CompanionAdmission.Provided NONE = CompanionAdmission.Provided.none();
    private static final String PASTURE = "runeteria:husbandry_deployable";
    private static final String BARN = "runeteria:husbandry_owned";
    private static final CompanionLocation LIVE = CompanionLocation.live("w", 0, 0, 0);

    private static DomainClaim deployable(int weight) {
        return new DomainClaim(PASTURE, weight, false, true);
    }

    private static DomainClaim ownedClaim(int weight) {
        return new DomainClaim(BARN, weight, true, false);
    }

    private static CompanionAdmission.Provided provided(DomainClaim claim, int limit) {
        return new CompanionAdmission.Provided(List.of(claim), Map.of(claim.domainId(), limit));
    }

    private CompanionRecord claiming(CompanionLocation at, DomainClaim... claims) {
        return rec("Cow", at).toBuilder().domainClaims(List.of(claims)).build();
    }

    private CompanionRecord rec(String role, CompanionLocation at) {
        return CompanionRecord.builder(UUID.randomUUID(), role, at).ownerUuid(owner).homeWorld("w").build();
    }

    private CompanionAdmission.Rules rules(int owned, boolean perWorld) {
        return new CompanionAdmission.Rules(owned, perWorld,
                role -> role.startsWith("Dragon") ? List.of(DRAGONS) : List.of());
    }

    @Test
    void aNewCompanionPastTheOwnedLimitIsRefused() {
        List<CompanionRecord> mine = List.of(rec("Sheep", CompanionLocation.item()), rec("Sheep", CompanionLocation.stored(StoredReason.ROSTER)));
        assertEquals(CompanionAdmission.Refusal.OWNED,
                CompanionAdmission.check(mine, null, rec("Sheep", CompanionLocation.live("w", 0, 0, 0)), rules(2, false), NONE));
        assertNull(CompanionAdmission.check(mine, null, rec("Sheep", CompanionLocation.live("w", 0, 0, 0)), rules(3, false), NONE));
    }

    @Test
    void aMoveThatAddsNothingIsAllowedEvenOverALoweredLimit() {
        CompanionRecord stored = rec("Sheep", CompanionLocation.stored(StoredReason.ROSTER));
        List<CompanionRecord> mine = List.of(stored, rec("Sheep", CompanionLocation.item()), rec("Sheep", CompanionLocation.item()));
        CompanionRecord summoned = stored.toBuilder().location(CompanionLocation.live("w", 0, 0, 0)).build();
        assertNull(CompanionAdmission.check(mine, stored, summoned, rules(1, false), NONE));
    }

    @Test
    void aCompanionThatHasBeenInNoWorldEntersItsFirstWorldLikeOneThatWasTamedThere() {
        // A provisioned or granted companion has no home world until its first summon. The owner
        // is over a per-world limit that was lowered, as a captured companion's owner can be.
        CompanionRecord captured = rec("Sheep", CompanionLocation.stored(StoredReason.BONDED));
        CompanionRecord provisioned = CompanionRecord.builder(UUID.randomUUID(), "Sheep",
                CompanionLocation.stored(StoredReason.PROVISIONED)).ownerUuid(owner).build();
        CompanionRecord dead = CompanionRecord.builder(UUID.randomUUID(), "Dragon_Fire",
                CompanionLocation.dead("UNKNOWN")).ownerUuid(owner).build();
        List<CompanionRecord> mine = List.of(captured, provisioned, dead,
                rec("Sheep", CompanionLocation.live("w", 0, 0, 0)), rec("Dragon_Ice", CompanionLocation.item()));
        PopulationGroupPolicy perWorld = new PopulationGroupPolicy("dragons", PopulationGroupScope.PER_WORLD, 1, 0, 1);
        CompanionAdmission.Rules rules = new CompanionAdmission.Rules(1, true,
                role -> role.startsWith("Dragon") ? List.of(perWorld) : List.of());

        for (CompanionRecord stored : List.of(captured, provisioned, dead)) {
            CompanionRecord summoned = stored.toBuilder().location(CompanionLocation.live("w", 0, 0, 0)).build();
            assertNull(CompanionAdmission.check(mine, stored, summoned, rules, NONE), stored.location().toString());
        }
        // A companion that has a home counts there, so another world's limit still applies to it.
        CompanionRecord elsewhere = captured.toBuilder().location(CompanionLocation.live("other", 0, 0, 0)).build();
        List<CompanionRecord> withOther = List.of(captured, rec("Sheep", CompanionLocation.live("other", 0, 0, 0)));
        assertEquals(CompanionAdmission.Refusal.OWNED,
                CompanionAdmission.check(withOther, captured, elsewhere, rules, NONE));
    }

    @Test
    void perWorldOwnedLimitsCountOnlyThatWorld() {
        List<CompanionRecord> mine = List.of(rec("Sheep", CompanionLocation.live("other", 0, 0, 0)));
        assertNull(CompanionAdmission.check(mine, null, rec("Sheep", CompanionLocation.live("w", 0, 0, 0)), rules(1, true), NONE));
        assertEquals(CompanionAdmission.Refusal.OWNED,
                CompanionAdmission.check(mine, null, rec("Sheep", CompanionLocation.live("other", 0, 0, 0)), rules(1, true), NONE));
    }

    @Test
    void groupLimitsCountOnlyTheirGroupAndActiveCountsOnlyLive() {
        CompanionRecord storedDragon = rec("Dragon_Fire", CompanionLocation.stored(StoredReason.ROSTER));
        List<CompanionRecord> mine = List.of(rec("Dragon_Ice", CompanionLocation.live("w", 0, 0, 0)), storedDragon,
                rec("Sheep", CompanionLocation.live("w", 0, 0, 0)));
        assertEquals(CompanionAdmission.Refusal.GROUP_OWNED,
                CompanionAdmission.check(mine, null, rec("Dragon_Ice", CompanionLocation.item()), rules(0, false), NONE));
        CompanionRecord summoned = storedDragon.toBuilder().location(CompanionLocation.live("w", 0, 0, 0)).build();
        assertEquals(CompanionAdmission.Refusal.GROUP_DEPLOYED, CompanionAdmission.check(mine, storedDragon, summoned, rules(0, false), NONE));
    }

    @Test
    void aDeployableClaimCountsTheWeightsOfTheOwnersDeployedRecords() {
        List<CompanionRecord> mine = List.of(
                claiming(LIVE, deployable(2)),
                claiming(CompanionLocation.stored(StoredReason.ROSTER), deployable(5)),
                rec("Sheep", LIVE));
        CompanionRecord tamed = rec("Cow", LIVE);

        assertNull(CompanionAdmission.check(mine, null, tamed, rules(0, false), provided(deployable(1), 3)));
        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED,
                CompanionAdmission.check(mine, null, tamed, rules(0, false), provided(deployable(2), 3)));
        assertEquals(new CompanionAdmission.DomainRefusal(PASTURE, "tamework.ui.population.deployedLimit"),
                CompanionAdmission.checkDomains(mine, null, tamed, provided(deployable(2), 3)));
    }

    @Test
    void aDeployableClaimIsCheckedWhenTheRecordBecomesLiveNotWhileItStaysLiveOrStored() {
        CompanionRecord stored = claiming(CompanionLocation.stored(StoredReason.ROSTER), deployable(1));
        CompanionRecord live = stored.toBuilder().location(LIVE).build();
        CompanionRecord other = claiming(LIVE, deployable(1));
        CompanionAdmission.Provided full = provided(deployable(1), 1);

        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED,
                CompanionAdmission.check(List.of(stored, other), stored, live, rules(0, false), full));
        assertNull(CompanionAdmission.check(List.of(live, other), live,
                live.toBuilder().location(CompanionLocation.live("w", 5, 0, 5)).build(), rules(0, false), full));
        assertNull(CompanionAdmission.check(List.of(other), null,
                rec("Cow", CompanionLocation.item()), rules(0, false), full));
    }

    @Test
    void anOwnedClaimCountsStoredRecordsAndIsCheckedForANewOwnerOnly() {
        CompanionRecord stored = claiming(CompanionLocation.stored(StoredReason.ROSTER), ownedClaim(1));
        List<CompanionRecord> mine = List.of(stored);
        CompanionAdmission.Provided full = provided(ownedClaim(1), 1);

        assertEquals(new CompanionAdmission.DomainRefusal(BARN, "tamework.ui.population.ownedLimit"),
                CompanionAdmission.checkDomains(mine, null, rec("Cow", CompanionLocation.item()), full));
        assertNull(CompanionAdmission.check(mine, stored, stored.toBuilder().location(LIVE).build(),
                rules(0, false), full));

        CompanionRecord theirs = claiming(LIVE, ownedClaim(1)).toBuilder().ownerUuid(UUID.randomUUID()).build();
        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED, CompanionAdmission.check(mine, theirs,
                theirs.toBuilder().ownerUuid(owner).build(), rules(0, false), full));
    }

    @Test
    void aDomainLimitOfZeroAdmitsNothingAndAClaimWithoutALimitIsRefused() {
        CompanionRecord tamed = rec("Cow", LIVE);

        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED,
                CompanionAdmission.check(List.of(), null, tamed, rules(0, false), provided(deployable(1), 0)));
        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED,
                CompanionAdmission.check(List.of(), null, tamed, rules(0, false),
                        new CompanionAdmission.Provided(List.of(deployable(1)), Map.of())));
    }

    @Test
    void aRecordThatAlreadyHoldsTheClaimIsCheckedOnlyWhenItsWeightGrows() {
        CompanionRecord mine = claiming(LIVE, deployable(2));
        CompanionRecord other = claiming(LIVE, deployable(2));
        CompanionRecord moved = mine.toBuilder().location(CompanionLocation.live("w", 9, 0, 9)).build();
        List<CompanionRecord> all = List.of(mine, other);

        // The owner is already over a lowered limit: the same or a smaller weight adds nothing.
        assertNull(CompanionAdmission.check(all, mine, moved, rules(0, false), provided(deployable(2), 3)));
        assertNull(CompanionAdmission.check(all, mine, moved, rules(0, false), provided(deployable(1), 3)));
        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED,
                CompanionAdmission.check(all, mine, moved, rules(0, false), provided(deployable(3), 4)));
        assertNull(CompanionAdmission.check(all, mine, moved, rules(0, false), provided(deployable(3), 5)));
    }

    @Test
    void anUnownedOrReleasedResultIsAlwaysAllowed() {
        CompanionRecord wild = CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.live("w", 0, 0, 0)).build();
        assertNull(CompanionAdmission.check(List.of(), null, wild, rules(0, false), NONE));
    }
}

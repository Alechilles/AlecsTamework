package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompanionAdmissionTest {
    private final UUID owner = UUID.randomUUID();
    private static final PopulationGroupPolicy DRAGONS =
            new PopulationGroupPolicy("dragons", PopulationGroupScope.GLOBAL, 2, 1, 1);

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
                CompanionAdmission.check(mine, null, rec("Sheep", CompanionLocation.live("w", 0, 0, 0)), rules(2, false)));
        assertNull(CompanionAdmission.check(mine, null, rec("Sheep", CompanionLocation.live("w", 0, 0, 0)), rules(3, false)));
    }

    @Test
    void aMoveThatAddsNothingIsAllowedEvenOverALoweredLimit() {
        CompanionRecord stored = rec("Sheep", CompanionLocation.stored(StoredReason.ROSTER));
        List<CompanionRecord> mine = List.of(stored, rec("Sheep", CompanionLocation.item()), rec("Sheep", CompanionLocation.item()));
        CompanionRecord summoned = stored.toBuilder().location(CompanionLocation.live("w", 0, 0, 0)).build();
        assertNull(CompanionAdmission.check(mine, stored, summoned, rules(1, false)));
    }

    @Test
    void perWorldOwnedLimitsCountOnlyThatWorld() {
        List<CompanionRecord> mine = List.of(rec("Sheep", CompanionLocation.live("other", 0, 0, 0)));
        assertNull(CompanionAdmission.check(mine, null, rec("Sheep", CompanionLocation.live("w", 0, 0, 0)), rules(1, true)));
        assertEquals(CompanionAdmission.Refusal.OWNED,
                CompanionAdmission.check(mine, null, rec("Sheep", CompanionLocation.live("other", 0, 0, 0)), rules(1, true)));
    }

    @Test
    void groupLimitsCountOnlyTheirGroupAndActiveCountsOnlyLive() {
        CompanionRecord storedDragon = rec("Dragon_Fire", CompanionLocation.stored(StoredReason.ROSTER));
        List<CompanionRecord> mine = List.of(rec("Dragon_Ice", CompanionLocation.live("w", 0, 0, 0)), storedDragon,
                rec("Sheep", CompanionLocation.live("w", 0, 0, 0)));
        assertEquals(CompanionAdmission.Refusal.GROUP_OWNED,
                CompanionAdmission.check(mine, null, rec("Dragon_Ice", CompanionLocation.item()), rules(0, false)));
        CompanionRecord summoned = storedDragon.toBuilder().location(CompanionLocation.live("w", 0, 0, 0)).build();
        assertEquals(CompanionAdmission.Refusal.GROUP_DEPLOYED, CompanionAdmission.check(mine, storedDragon, summoned, rules(0, false)));
    }

    @Test
    void anUnownedOrReleasedResultIsAlwaysAllowed() {
        CompanionRecord wild = CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.live("w", 0, 0, 0)).build();
        assertNull(CompanionAdmission.check(List.of(), null, wild, rules(0, false)));
    }

    @Test
    void aBatchIsCheckedAsAWhole() {
        List<CompanionRecord> mine = List.of(rec("Sheep", CompanionLocation.item()));
        List<CompanionRecord> litter = List.of(rec("Sheep", CompanionLocation.live("w", 0, 0, 0)), rec("Sheep", CompanionLocation.live("w", 0, 0, 0)));
        assertEquals(CompanionAdmission.Refusal.OWNED, CompanionAdmission.checkBatch(mine, litter, rules(2, false)));
        assertNull(CompanionAdmission.checkBatch(mine, litter, rules(3, false)));
    }
}

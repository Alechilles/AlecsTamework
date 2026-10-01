package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
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

class CompanionAdmissionGateTest {
    private static final PopulationGroupPolicy DRAGONS =
            new PopulationGroupPolicy("dragons", PopulationGroupScope.GLOBAL, 2, 1, 1);

    private final UUID owner = UUID.randomUUID();
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final CompanionAdmissionGate gate = CompanionAdmissionGate.withRules(index,
            () -> new CompanionAdmission.Rules(0, false,
                    role -> role.startsWith("Dragon") ? List.of(DRAGONS) : List.of()));

    private void own(String role, CompanionLocation at) {
        index.insert(CompanionRecord.builder(UUID.randomUUID(), role, at).ownerUuid(owner).homeWorld("w").build());
    }

    /** The tame sites refuse before the tame runs, so a group-capped tame never has to be undone. */
    @Test
    void thePrecheckRefusesANewCompanionAtAGroupLimit() {
        own("Dragon_Ice", CompanionLocation.live("w", 0, 0, 0));

        assertEquals(CompanionAdmission.Refusal.GROUP_DEPLOYED, gate.precheck(owner, "Dragon_Fire", "w"));
        assertNull(gate.precheck(owner, "Sheep", "w"), "other roles are not in the group");

        own("Dragon_Fire", CompanionLocation.stored(StoredReason.ROSTER));
        assertEquals(CompanionAdmission.Refusal.GROUP_OWNED, gate.precheck(owner, "Dragon_Fire", "w"));
    }

    /** A capture into an item creates an ITEM record: the deployed limit does not apply, the owned one does. */
    @Test
    void aCaptureStylePrecheckIgnoresTheDeployedLimitButNotTheOwnedOne() {
        own("Dragon_Ice", CompanionLocation.live("w", 0, 0, 0));

        assertNull(gate.precheck(owner, "Dragon_Fire", "w", false));

        own("Dragon_Fire", CompanionLocation.stored(StoredReason.ROSTER));
        assertEquals(CompanionAdmission.Refusal.GROUP_OWNED, gate.precheck(owner, "Dragon_Fire", "w", false));
    }

    /** The flows show the denial's key, so a provider domain limit must not read as a group limit. */
    @Test
    void aDenialNamesTheMessageOfTheCapOrTheDomainLimitThatRefused() {
        DomainClaim pasture = new DomainClaim("pasture", 2, false, true);
        index.insert(CompanionRecord.builder(UUID.randomUUID(), "Cow", CompanionLocation.live("w", 0, 0, 0))
                .ownerUuid(owner).domainClaims(List.of(pasture)).build());
        own("Dragon_Ice", CompanionLocation.live("w", 0, 0, 0));
        CompanionRecord cow = CompanionRecord.builder(UUID.randomUUID(), "Cow", CompanionLocation.live("w", 0, 0, 0))
                .ownerUuid(owner).build();
        CompanionRecord dragon = CompanionRecord.builder(UUID.randomUUID(), "Dragon_Fire",
                CompanionLocation.live("w", 0, 0, 0)).ownerUuid(owner).build();

        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED,
                        CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY),
                gate.deny(null, cow, new CompanionAdmission.Provided(List.of(pasture), Map.of("pasture", 3))));
        assertNull(gate.deny(null, cow, new CompanionAdmission.Provided(List.of(pasture), Map.of("pasture", 4))));
        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.GROUP_DEPLOYED,
                        CompanionAdmissionGate.GROUP_LIMIT_MESSAGE_KEY),
                gate.deny(null, dragon, CompanionAdmission.Provided.none()));
    }
}

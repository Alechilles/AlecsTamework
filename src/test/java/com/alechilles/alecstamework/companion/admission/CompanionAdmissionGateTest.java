package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.companion.flow.CompanionRegistration;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        own("Dragon_Ice", CompanionLocation.live("w", 1, 64, 1));

        assertEquals(CompanionAdmission.Refusal.GROUP_DEPLOYED, gate.precheck(owner, "Dragon_Fire", "w"));
        assertNull(gate.precheck(owner, "Sheep", "w"), "other roles are not in the group");

        own("Dragon_Fire", CompanionLocation.stored(StoredReason.ROSTER));
        assertEquals(CompanionAdmission.Refusal.GROUP_OWNED, gate.precheck(owner, "Dragon_Fire", "w"));
    }

    /** A capture into an item creates an ITEM record: the deployed limit does not apply, the owned one does. */
    @Test
    void aCaptureStylePrecheckIgnoresTheDeployedLimitButNotTheOwnedOne() {
        own("Dragon_Ice", CompanionLocation.live("w", 1, 64, 1));

        assertNull(gate.precheck(owner, "Dragon_Fire", "w", false));

        own("Dragon_Fire", CompanionLocation.stored(StoredReason.ROSTER));
        assertEquals(CompanionAdmission.Refusal.GROUP_OWNED, gate.precheck(owner, "Dragon_Fire", "w", false));
    }

    /** A tame makes a companion that is out in the world; a capture into an item does not. */
    @Test
    void thePrecheckRefusesATameAtTheDeployedLimitWithItsOwnMessageButNotACapture() {
        CompanionAdmissionGate capped = CompanionAdmissionGate.withRules(index,
                () -> new CompanionAdmission.Rules(0, 1, false, role -> List.of()));
        own("Sheep", CompanionLocation.live("w", 1, 64, 1));

        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.DEPLOYED,
                CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY), capped.precheckDenial(owner, "Sheep", "w", true));
        assertNull(capped.precheckDenial(owner, "Sheep", "w", false));
    }

    /** The flows show the denial's key, so a provider domain limit must not read as a group limit. */
    @Test
    void aDenialNamesTheMessageOfTheCapOrTheDomainLimitThatRefused() {
        DomainClaim pasture = new DomainClaim("pasture", 2, false, true);
        index.insert(CompanionRecord.builder(UUID.randomUUID(), "Cow", CompanionLocation.live("w", 1, 64, 1))
                .ownerUuid(owner).domainClaims(List.of(pasture)).build());
        own("Dragon_Ice", CompanionLocation.live("w", 1, 64, 1));
        CompanionRecord cow = CompanionRecord.builder(UUID.randomUUID(), "Cow", CompanionLocation.live("w", 1, 64, 1))
                .ownerUuid(owner).build();
        CompanionRecord dragon = CompanionRecord.builder(UUID.randomUUID(), "Dragon_Fire",
                CompanionLocation.live("w", 1, 64, 1)).ownerUuid(owner).build();

        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED,
                        CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY),
                gate.deny(null, cow, new CompanionAdmission.Provided(List.of(pasture), Map.of("pasture", 3))));
        assertNull(gate.deny(null, cow, new CompanionAdmission.Provided(List.of(pasture), Map.of("pasture", 4))));
        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.GROUP_DEPLOYED,
                        CompanionAdmissionGate.GROUP_LIMIT_MESSAGE_KEY),
                gate.deny(null, dragon, CompanionAdmission.Provided.none()));
    }

    /** Spec 8.11: a litter is admitted as a whole or not at all. */
    @Test
    void aLitterIsCheckedAsAWholeAgainstTheGroupLimit() {
        CompanionRecord one = live("Dragon_Fire");
        CompanionRecord two = live("Dragon_Ice").toBuilder().location(CompanionLocation.item()).build();
        CompanionRecord three = live("Dragon_Ice").toBuilder().location(CompanionLocation.item()).build();

        assertNull(gate.denyBatch(owner, List.of(one, two)));
        assertEquals(CompanionAdmission.Refusal.GROUP_OWNED, gate.denyBatch(owner, List.of(one, two, three)).refusal());
    }

    // --- Cached provider decisions at the synchronous sites (plan 6 R11) ---

    private static final String PASTURE = "runeteria:husbandry_deployable";
    private static final DomainClaim PASTURE_CLAIM = new DomainClaim(PASTURE, 1, false, true);

    /** What the provider answers next; null leaves the question open, as a slow provider does. */
    private PopulationAdmissionProviderDecision answer;
    private int asked;
    private final ProviderDecisionCache decisions = new ProviderDecisionCache(new ProviderAdmission(
            role -> role.equals("Cow") ? new ProviderAdmission.Managed("runeteria:husbandry", 1,
                    "runeteria:husbandry", "cattle", Set.of("cattle"), "husbandry.cattle", 1, 7L) : null,
            request -> {
                asked++;
                return answer == null ? new CompletableFuture<>() : CompletableFuture.completedFuture(answer);
            }), System::currentTimeMillis);
    private final CompanionAdmissionGate managedGate = CompanionAdmissionGate.withRules(index,
            () -> new CompanionAdmission.Rules(3, false, role -> List.of()), decisions);

    private static PopulationAdmissionProviderDecision allow(int pastureLimit) {
        return new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.ALLOW, "ok",
                Set.of(new PopulationDomainClaim(PASTURE, 1, false, true)), Map.of(PASTURE, pastureLimit), 1L, 7L);
    }

    private CompanionRecord live(String role) {
        return CompanionRecord.builder(UUID.randomUUID(), role, CompanionLocation.live("w", 1, 64, 1))
                .ownerUuid(owner).homeWorld("w").currentNpcUuid(UUID.randomUUID()).build();
    }

    /** Review Focus 2: a slow provider never holds the check; the change is refused and nothing is stored. */
    @Test
    void aManagedRoleIsRefusedWithCheckingWhileItsDecisionIsFetched() {
        CompanionAdmissionGate.Admission admission = managedGate.admit(null, live("Cow"));

        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE,
                ProviderDecisionCache.CHECKING_MESSAGE_KEY), admission.denial());
        assertEquals(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE, managedGate.precheck(owner, "Cow", "w"));
        assertEquals(1, asked, "the precheck did not start a second evaluation");
        assertNull(managedGate.admit(null, live("Sheep")).denial(), "an unmanaged role does not wait for a provider");
    }

    @Test
    void aCachedDenialOrUnavailableProviderRefusesWithItsOwnMessage() {
        answer = new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.DENY,
                "runeteria.husbandry.locked", Set.of(), Map.of(), 1L, 7L);
        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED,
                "runeteria.husbandry.locked"), managedGate.precheckDenial(owner, "Cow", "w", true));

        decisions.clear();
        answer = PopulationAdmissionProviderDecision.unavailable("provider-timeout");
        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE,
                CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY), managedGate.admit(null, live("Cow")).denial());
    }

    @Test
    void aBuiltInCapRefusesBeforeAnyProviderIsAsked() {
        own("Sheep", CompanionLocation.item());
        own("Sheep", CompanionLocation.item());
        own("Sheep", CompanionLocation.item());

        assertEquals(CompanionAdmission.Refusal.OWNED, managedGate.refuse(null, live("Cow")));
        assertEquals(0, asked);
    }

    /** The tame re-check: the record that is registered carries the claims of the cached allow. */
    @Test
    void aTameOfAManagedRoleWithACachedAllowStoresTheClaimsOnTheRecord() {
        answer = allow(2);
        CompanionRecord cow = live("Cow");

        CompanionRegistration.Outcome outcome = CompanionRegistration.registerAdmitted(index,
                new LoadedBodies<String>(), cow, "body", record -> managedGate.admit(null, record));

        assertTrue(outcome.registered());
        assertEquals(List.of(PASTURE_CLAIM), index.get(cow.profileId()).domainClaims());
    }

    @Test
    void aCachedAllowIsStillHeldToItsDomainLimitUnderTheLock() {
        answer = allow(1);
        CompanionRecord first = live("Cow");
        CompanionRecord second = live("Cow");
        LoadedBodies<String> loaded = new LoadedBodies<>();
        assertTrue(CompanionRegistration.registerAdmitted(index, loaded, first, "a",
                record -> managedGate.admit(null, record)).registered());

        CompanionRegistration.Outcome outcome = CompanionRegistration.registerAdmitted(index, loaded, second, "b",
                record -> managedGate.admit(null, record));

        assertFalse(outcome.registered());
        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED, outcome.refusal());
        assertEquals(CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY, outcome.messageKey());
        assertNull(index.get(second.profileId()));
    }

    /** A litter of a managed role counts each child's claim; a miss refuses the whole litter. */
    @Test
    void aLitterOfAManagedRoleUsesTheCachedDecisionAndItsDomainLimit() {
        assertEquals(ProviderDecisionCache.CHECKING_MESSAGE_KEY,
                managedGate.denyBatch(owner, List.of(live("Cow"), live("Cow"))).messageKey());

        decisions.clear();
        answer = allow(2);
        assertNull(managedGate.denyBatch(owner, List.of(live("Cow"), live("Cow"))));
        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED,
                        CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY),
                managedGate.denyBatch(owner, List.of(live("Cow"), live("Cow"), live("Cow"))));
    }
}

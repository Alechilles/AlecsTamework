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

    /** Server networks: a dragon out in another server's world fills the group's active limit here too. */
    @Test
    void companionsHeldByAnotherServerCountTowardTheOwnersLimits() {
        CompanionRecord onServerB = CompanionRecord.builder(UUID.randomUUID(), "Dragon_Ice",
                CompanionLocation.live("B/w", 1, 64, 1)).ownerUuid(owner).homeWorld("B/w").build();
        assertNull(gate.precheck(owner, "Dragon_Fire", "w"));

        gate.useOwnerRecords(o -> {
            List<CompanionRecord> all = new java.util.ArrayList<>(index.fileRecords(o));
            all.add(onServerB);
            return all;
        });

        assertEquals(CompanionAdmission.Refusal.GROUP_DEPLOYED, gate.precheck(owner, "Dragon_Fire", "w"));
    }

    /** A world where companions may not come out: no tame or release there, but items and moves within it pass. */
    @Test
    void aBlockedWorldRefusesCompanionsComingOutButNotItemsOrCompanionsAlreadyThere() {
        gate.useDeployRule(world -> !"hub".equals(world));
        CompanionRecord lantern = CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.item())
                .ownerUuid(owner).homeWorld("w").build();
        CompanionRecord alreadyOut = CompanionRecord.builder(UUID.randomUUID(), "Sheep",
                CompanionLocation.live("hub", 1, 64, 1)).ownerUuid(owner).homeWorld("hub").build();

        assertEquals(CompanionAdmission.Refusal.DEPLOY_BLOCKED, gate.precheck(owner, "Sheep", "hub"), "no tame");
        assertNull(gate.precheck(owner, "Sheep", "hub", false), "a capture into an item is fine");
        assertEquals(CompanionAdmission.Refusal.DEPLOY_BLOCKED,
                gate.refuse(lantern, lantern.toBuilder().location(CompanionLocation.live("hub", 5, 64, 5)).build()),
                "no release from a lantern");
        assertNull(gate.refuse(alreadyOut, alreadyOut.toBuilder()
                .location(CompanionLocation.live("hub", 9, 64, 9)).build()), "one already out may move about");
        assertNull(gate.refuse(lantern, lantern.toBuilder().location(CompanionLocation.live("w", 5, 64, 5)).build()),
                "other worlds are fine");
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

    // --- Admission providers at the synchronous sites ---

    private static final String PASTURE = "runeteria:husbandry_deployable";
    private static final DomainClaim PASTURE_CLAIM = new DomainClaim(PASTURE, 1, false, true);

    /** What the provider answers; null makes it throw. */
    private PopulationAdmissionProviderDecision answer;
    private int asked;
    private final ProviderAdmission providers = new ProviderAdmission(
            role -> role.equals("Cow") ? new ProviderAdmission.Managed("runeteria:husbandry", 1,
                    "runeteria:husbandry", "cattle", Set.of("cattle"), "husbandry.cattle", 1, 7L) : null,
            request -> {
                asked++;
                if (answer == null) {
                    throw new IllegalStateException("provider failed");
                }
                return answer;
            });
    private final CompanionAdmissionGate managedGate = CompanionAdmissionGate.withRules(index,
            () -> new CompanionAdmission.Rules(3, false, role -> List.of()), providers);

    private static PopulationAdmissionProviderDecision allow(int pastureLimit) {
        return new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.ALLOW, "ok",
                Set.of(new PopulationDomainClaim(PASTURE, 1, false, true)), Map.of(PASTURE, pastureLimit), 1L, 7L);
    }

    private CompanionRecord live(String role) {
        return CompanionRecord.builder(UUID.randomUUID(), role, CompanionLocation.live("w", 1, 64, 1))
                .ownerUuid(owner).homeWorld("w").currentNpcUuid(UUID.randomUUID()).build();
    }

    /** The first tame of a managed role is decided at once: no "try again" while a provider is asked. */
    @Test
    void theFirstCheckOfAManagedRoleGetsTheProvidersAnswer() {
        answer = allow(2);

        assertNull(managedGate.precheck(owner, "Cow", "w"));
        CompanionAdmissionGate.Admission admission = managedGate.admit(null, live("Cow"));

        assertTrue(admission.admitted());
        assertEquals(List.of(PASTURE_CLAIM), admission.record().domainClaims());
        assertNull(managedGate.admit(null, live("Sheep")).denial(), "an unmanaged role asks no provider");
        assertEquals(2, asked);
    }

    @Test
    void aDenialOrUnavailableOrFailingProviderRefusesWithItsMessage() {
        answer = new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.DENY,
                "runeteria.husbandry.locked", Set.of(), Map.of(), 1L, 7L);
        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED,
                "runeteria.husbandry.locked"), managedGate.precheckDenial(owner, "Cow", "w", true));

        CompanionAdmissionGate.Denial unavailable = new CompanionAdmissionGate.Denial(
                CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE, CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY);
        answer = PopulationAdmissionProviderDecision.unavailable("profile-not-loaded");
        assertEquals(unavailable, managedGate.admit(null, live("Cow")).denial());
        answer = null;
        assertEquals(unavailable, managedGate.admit(null, live("Cow")).denial());
    }

    @Test
    void aBuiltInCapRefusesBeforeAnyProviderIsAsked() {
        own("Sheep", CompanionLocation.item());
        own("Sheep", CompanionLocation.item());
        own("Sheep", CompanionLocation.item());

        assertEquals(CompanionAdmission.Refusal.OWNED, managedGate.refuse(null, live("Cow")));
        assertEquals(0, asked);
    }

    /** The tame re-check: the record that is registered carries the claims of the allow. */
    @Test
    void aTameOfAManagedRoleStoresTheProvidersClaimsOnTheRecord() {
        answer = allow(2);
        CompanionRecord cow = live("Cow");

        CompanionRegistration.Outcome outcome = CompanionRegistration.registerAdmitted(index,
                new LoadedBodies<String>(), cow, "body", record -> managedGate.admit(null, record));

        assertTrue(outcome.registered());
        assertEquals(List.of(PASTURE_CLAIM), index.get(cow.profileId()).domainClaims());
    }

    @Test
    void anAllowIsHeldToItsDomainLimitUnderTheLock() {
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

    /** A litter of a managed role counts each child's claim; one child over the limit refuses the litter. */
    @Test
    void aLitterOfAManagedRoleIsHeldToTheProvidersDomainLimit() {
        answer = allow(2);
        assertNull(managedGate.denyBatch(owner, List.of(live("Cow"), live("Cow"))));
        assertEquals(new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED,
                        CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY),
                managedGate.denyBatch(owner, List.of(live("Cow"), live("Cow"), live("Cow"))));
    }
}

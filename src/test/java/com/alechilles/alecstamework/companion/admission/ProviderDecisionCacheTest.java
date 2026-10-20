package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderDecisionCacheTest {
    private static final String PASTURE = "runeteria:husbandry_deployable";
    private static final ProviderAdmission.Managed COWS = new ProviderAdmission.Managed("runeteria:husbandry", 1,
            "runeteria:husbandry", "cattle", Set.of("cattle", "sheep"), "husbandry.cattle", 1, 7L);
    private static final ProviderAdmission.Managed SHEEP = new ProviderAdmission.Managed("runeteria:husbandry", 1,
            "runeteria:husbandry", "sheep", Set.of("cattle", "sheep"), "husbandry.sheep", 1, 7L);
    private static final List<DomainClaim> CLAIMS = List.of(new DomainClaim(PASTURE, 1, false, true));

    private final UUID owner = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_000L);
    private final AtomicReference<Object> registrations = new AtomicReference<>("none");
    /** One entry per provider call: the request and the answer the test completes. */
    private final List<PopulationAdmissionProviderRequest> asked = new ArrayList<>();
    private final List<CompletableFuture<PopulationAdmissionProviderDecision>> answers = new ArrayList<>();
    private final ProviderDecisionCache cache = new ProviderDecisionCache(new ProviderAdmission(
            role -> role.equals("Cow") ? COWS : role.equals("Sheep") ? SHEEP : null,
            request -> {
                asked.add(request);
                CompletableFuture<PopulationAdmissionProviderDecision> answer = new CompletableFuture<>();
                answers.add(answer);
                return answer;
            },
            () -> List.of("Cow", "Sheep"), registrations::get), now::get);

    private static PopulationAdmissionProviderDecision allow() {
        return new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.ALLOW, "ok",
                Set.of(new PopulationDomainClaim(PASTURE, 1, false, true)), Map.of(PASTURE, 2), 1L, 7L);
    }

    private static PopulationAdmissionProviderDecision deny() {
        return new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.DENY,
                "runeteria.husbandry.locked", Set.of(), Map.of(), 1L, 7L);
    }

    private CompanionRecord live(String role) {
        return CompanionRecord.builder(UUID.randomUUID(), role, CompanionLocation.live("w", 0, 0, 0))
                .ownerUuid(owner).homeWorld("w").build();
    }

    private void assertChecking(ProviderAdmission.Outcome outcome) {
        assertEquals(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE, outcome.refusal());
        assertEquals(ProviderDecisionCache.CHECKING_MESSAGE_KEY, outcome.messageKey());
    }

    /** Review Focus 2: the caller never waits; it is refused while one evaluation runs. */
    @Test
    void aMissRefusesWithCheckingAndStartsOneEvaluationWhoseAnswerIsThenUsed() {
        assertChecking(cache.decide(null, live("Cow")));
        assertChecking(cache.decide(null, live("Cow")));
        assertEquals(1, asked.size(), "a second attempt does not ask the provider again");

        answers.get(0).complete(allow());
        ProviderAdmission.Outcome hit = cache.decide(null, live("Cow"));

        assertTrue(hit.admitted());
        assertEquals(CLAIMS, hit.provided().claims());
        assertEquals(Map.of(PASTURE, 2), hit.provided().domainLimits());
        assertEquals(1, asked.size());
    }

    @Test
    void aDecisionLivesThirtySecondsAndIsKeptPerFamily() {
        cache.decide(null, live("Cow"));
        answers.get(0).complete(allow());

        assertChecking(cache.decide(null, live("Sheep")));
        assertEquals("sheep", asked.get(1).familyGroupId(), "another family of the profile is its own decision");

        now.addAndGet(ProviderDecisionCache.TTL_MS - 1);
        assertTrue(cache.decide(null, live("Cow")).admitted());
        now.addAndGet(1);
        assertChecking(cache.decide(null, live("Cow")));
        assertEquals(3, asked.size());
    }

    @Test
    void aDenialKeepsTheProvidersMessageKey() {
        cache.decide(null, live("Cow"));
        answers.get(0).complete(deny());

        ProviderAdmission.Outcome hit = cache.decide(null, live("Cow"));

        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED, hit.refusal());
        assertEquals("runeteria.husbandry.locked", hit.messageKey());
    }

    /** Review Focus 2: a provider that fails is refused as unavailable, and asked again soon. */
    @Test
    void aFailingProviderIsRefusedAsUnavailableAndAskedAgainAfterAFewSeconds() {
        cache.decide(null, live("Cow"));
        answers.get(0).completeExceptionally(new IllegalStateException("boom"));

        ProviderAdmission.Outcome hit = cache.decide(null, live("Cow"));
        assertEquals(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE, hit.refusal());
        assertEquals(CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY, hit.messageKey());
        assertEquals(1, asked.size());

        now.addAndGet(ProviderDecisionCache.UNAVAILABLE_TTL_MS);
        assertChecking(cache.decide(null, live("Cow")));
        assertEquals(2, asked.size());
    }

    @Test
    void aChangeThatNeedsNoAdmissionOrAnUnmanagedRoleNeverAsksAProvider() {
        CompanionRecord cow = live("Cow");

        assertFalse(cache.decide(null, live("Wolf")).asked());
        assertFalse(cache.decide(cow, cow.toBuilder().displayName("Bessie").build()).asked());
        assertTrue(asked.isEmpty());
    }

    @Test
    void anOwnersDecisionsAreDroppedWhenTheOwnersCountsChange() {
        cache.decide(null, live("Cow"));
        answers.get(0).complete(allow());
        CompanionRecord other = live("Wolf");

        cache.onRecordChanged(other, other.toBuilder().displayName("Rex").build());
        assertTrue(cache.decide(null, live("Cow")).admitted(), "a rename moves no count");

        cache.onRecordChanged(other, other.toBuilder().location(CompanionLocation.item()).build());
        assertChecking(cache.decide(null, live("Cow")));
    }

    /** Else the second child of a litter would be refused while the provider is asked again. */
    @Test
    void theAdmissionACachedAllowProducedDoesNotDropIt() {
        cache.decide(null, live("Cow"));
        answers.get(0).complete(allow());

        cache.onRecordChanged(null, live("Cow").toBuilder().domainClaims(CLAIMS).build());

        assertTrue(cache.decide(null, live("Cow")).admitted());
        assertEquals(1, asked.size());
    }

    @Test
    void anAnswerThatArrivesAfterItsOwnerWasInvalidatedIsNotStored() {
        cache.decide(null, live("Cow"));
        CompanionRecord gone = live("Wolf");
        cache.onRecordChanged(gone, gone.toBuilder().ownerUuid(null).build());

        answers.get(0).complete(allow());

        assertChecking(cache.decide(null, live("Cow")));
        assertEquals(2, asked.size());
    }

    @Test
    void aReloadAProviderRegistrationChangeAndADisconnectDropDecisions() {
        cache.decide(null, live("Cow"));
        answers.get(0).complete(allow());

        cache.clear();
        assertChecking(cache.decide(null, live("Cow")));
        answers.get(1).complete(allow());

        registrations.set("runeteria registered");
        assertChecking(cache.decide(null, live("Cow")));
        answers.get(2).complete(allow());

        cache.forgetOwner(owner);
        assertChecking(cache.decide(null, live("Cow")));
        assertEquals(4, asked.size());
    }

    @Test
    void aJoinWarmsEachManagedFamilyOneAfterTheOther() {
        cache.warm(owner, "w");
        assertEquals(1, asked.size(), "the provider's bounded queue is not filled by one join");
        assertEquals(owner, asked.get(0).admission().request().request().newOwnerUuid());

        answers.get(0).complete(allow());
        assertEquals(2, asked.size());
        answers.get(1).complete(allow());

        assertTrue(cache.decide(null, live("Cow")).admitted());
        assertTrue(cache.decide(null, live("Sheep")).admitted());
        assertEquals(2, asked.size());
    }

    @Test
    void afterCloseNothingIsStartedOrStored() {
        cache.decide(null, live("Cow"));
        cache.close();
        answers.get(0).complete(allow());

        assertChecking(cache.decide(null, live("Cow")));
        cache.warm(owner, "w");
        assertEquals(1, asked.size());
        assertNull(cache.decide(null, live("Wolf")).refusal(), "an unmanaged role is still admitted");
    }
}

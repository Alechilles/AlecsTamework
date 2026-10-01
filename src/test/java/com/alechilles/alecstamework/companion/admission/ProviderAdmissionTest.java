package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import java.time.Duration;
import java.util.ArrayList;
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

class ProviderAdmissionTest {
    private static final String PASTURE = "runeteria:husbandry_deployable";
    private static final String BARN = "runeteria:husbandry_owned";
    private static final ProviderAdmission.Managed COWS = new ProviderAdmission.Managed("runeteria:husbandry", 1,
            "runeteria:husbandry", "cattle", Set.of("cattle", "sheep"), "husbandry.cattle", 2, 7L);

    private final UUID owner = UUID.randomUUID();
    private final List<PopulationAdmissionProviderRequest> asked = new ArrayList<>();
    private PopulationAdmissionProviderDecision decision;
    private final ProviderAdmission admission = new ProviderAdmission(
            role -> role.equals("Cow") ? COWS : null,
            request -> {
                asked.add(request);
                return CompletableFuture.completedFuture(decision);
            });

    private static PopulationAdmissionProviderDecision allow(Set<PopulationDomainClaim> claims,
                                                             Map<String, Integer> limits) {
        return new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.ALLOW, "ok", claims, limits,
                1L, 7L);
    }

    private CompanionRecord cow(CompanionLocation at) {
        return CompanionRecord.builder(UUID.randomUUID(), "Cow", at).ownerUuid(owner).homeWorld("w")
                .currentNpcUuid(UUID.randomUUID()).build();
    }

    private ProviderAdmission.Outcome evaluate(CompanionRecord before, CompanionRecord after) {
        return admission.evaluate(before, after).toCompletableFuture().join();
    }

    @Test
    void anAllowGivesTheClaimsAndLimitsAndTheRequestNamesTheOwnersAndTheManagedProfile() {
        decision = allow(Set.of(new PopulationDomainClaim(PASTURE, 2, false, true),
                new PopulationDomainClaim(BARN, 1, true, false)), Map.of(PASTURE, 6, BARN, 10));
        CompanionRecord item = cow(CompanionLocation.item());
        CompanionRecord live = item.toBuilder().location(CompanionLocation.live("w", 70, 0, -1)).build();

        ProviderAdmission.Outcome outcome = evaluate(item, live);

        assertTrue(outcome.admitted());
        assertTrue(outcome.asked());
        assertEquals(List.of(new DomainClaim(PASTURE, 2, false, true), new DomainClaim(BARN, 1, true, false)),
                outcome.provided().claims());
        assertEquals(Map.of(PASTURE, 6, BARN, 10), outcome.provided().domainLimits());
        PopulationAdmissionProviderRequest request = asked.get(0);
        assertEquals("runeteria:husbandry", request.providerId());
        assertEquals(1, request.contractVersion());
        assertEquals("cattle", request.familyGroupId());
        assertEquals(2, request.weight());
        assertEquals(7L, request.managedConfigRevision());
        assertEquals("runeteria:husbandry", request.admission().managedProfileId());
        assertEquals("Cow", request.admission().request().targetRoleId());
        PopulationAdmissionRequest base = request.admission().request().request();
        assertEquals(PopulationAdmissionOperation.RESTORE, base.operation());
        assertEquals(PopulationCompanionLifecycle.ACTIVE, base.targetLifecycle());
        assertEquals(owner, base.oldOwnerUuid());
        assertEquals(owner, base.newOwnerUuid());
        assertEquals(2, base.destination().chunkX());
        assertEquals(-1, base.destination().chunkZ());
    }

    @Test
    void aNewOwnerIsPutToTheProviderWithBothOwners() {
        decision = allow(Set.of(), Map.of());
        CompanionRecord theirs = cow(CompanionLocation.live("w", 0, 0, 0)).toBuilder().ownerUuid(UUID.randomUUID()).build();
        CompanionRecord mine = theirs.toBuilder().location(CompanionLocation.item()).ownerUuid(owner).build();

        assertTrue(evaluate(theirs, mine).admitted());

        PopulationAdmissionRequest base = asked.get(0).admission().request().request();
        assertEquals(PopulationAdmissionOperation.OWNER_TRANSFER, base.operation());
        assertEquals(theirs.ownerUuid(), base.oldOwnerUuid());
        assertEquals(owner, base.newOwnerUuid());
        assertEquals(PopulationCompanionLifecycle.CAPTURED, base.targetLifecycle());

        asked.clear();
        assertTrue(evaluate(null, cow(CompanionLocation.item())).admitted());
        base = asked.get(0).admission().request().request();
        assertEquals(PopulationAdmissionOperation.NEW_OWNERSHIP, base.operation());
        assertNull(base.oldOwnerUuid());
        assertEquals(owner, base.newOwnerUuid());
    }

    @Test
    void aChangeThatAddsNothingOrAnUnmanagedRoleIsNotPutToTheProvider() {
        CompanionRecord live = cow(CompanionLocation.live("w", 0, 0, 0));
        CompanionRecord sheep = CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.live("w", 0, 0, 0))
                .ownerUuid(owner).build();

        ProviderAdmission.Outcome captured = evaluate(live, live.toBuilder().location(CompanionLocation.item()).build());
        ProviderAdmission.Outcome unmanaged = evaluate(null, sheep);
        ProviderAdmission.Outcome unowned = evaluate(live, live.toBuilder().ownerUuid(null).build());

        assertTrue(asked.isEmpty());
        for (ProviderAdmission.Outcome outcome : List.of(captured, unmanaged, unowned)) {
            assertTrue(outcome.admitted());
            assertFalse(outcome.asked());
            assertTrue(outcome.provided().claims().isEmpty());
        }
    }

    @Test
    void aDenyRefusesWithTheProvidersMessageKey() {
        decision = new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.DENY,
                "runeteria.husbandry.levelTooLow", Set.of(), Map.of(), 1L, 7L);

        ProviderAdmission.Outcome outcome = evaluate(null, cow(CompanionLocation.live("w", 0, 0, 0)));

        assertEquals(CompanionAdmission.Refusal.PROVIDER_DENIED, outcome.refusal());
        assertEquals("runeteria.husbandry.levelTooLow", outcome.messageKey());
    }

    @Test
    void anUnavailableProviderOrAStaleConfigRevisionRefusesWithTheUnavailableMessage() {
        CompanionRecord tamed = cow(CompanionLocation.live("w", 0, 0, 0));

        decision = PopulationAdmissionProviderDecision.unavailable("provider-timeout");
        ProviderAdmission.Outcome unavailable = evaluate(null, tamed);
        decision = new PopulationAdmissionProviderDecision(PopulationAdmissionProviderStatus.ALLOW, "ok", Set.of(),
                Map.of(), 1L, 6L);
        ProviderAdmission.Outcome stale = evaluate(null, tamed);

        for (ProviderAdmission.Outcome outcome : List.of(unavailable, stale)) {
            assertEquals(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE, outcome.refusal());
            assertEquals(CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY, outcome.messageKey());
        }
    }

    @Test
    void anAllowWithAClaimThatHasNoLimitOrClaimsABucketTwiceIsRefusedAsUnavailable() {
        CompanionRecord tamed = cow(CompanionLocation.live("w", 0, 0, 0));

        decision = allow(Set.of(new PopulationDomainClaim(PASTURE, 1, false, true)), Map.of(BARN, 3));
        ProviderAdmission.Outcome noLimit = evaluate(null, tamed);
        decision = allow(Set.of(new PopulationDomainClaim(PASTURE, 1, false, true),
                new PopulationDomainClaim(PASTURE, 2, true, true)), Map.of(PASTURE, 9));
        ProviderAdmission.Outcome twice = evaluate(null, tamed);

        assertEquals(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE, noLimit.refusal());
        assertEquals(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE, twice.refusal());
    }

    @Test
    void aManagedRoleWhoseProviderIsMissingOrNeverAnswersIsRefusedAsUnavailable() {
        try (AdmissionProviderRegistry registry = new AdmissionProviderRegistry(Duration.ofMillis(50))) {
            ProviderAdmission real = new ProviderAdmission(role -> COWS, registry::evaluate);
            CompanionRecord tamed = cow(CompanionLocation.live("w", 0, 0, 0));

            ProviderAdmission.Outcome missing = real.evaluate(null, tamed).toCompletableFuture().join();
            registry.register("runeteria:husbandry", 1, request -> new CompletableFuture<>());
            ProviderAdmission.Outcome timedOut = real.evaluate(null, tamed).toCompletableFuture().join();

            for (ProviderAdmission.Outcome outcome : List.of(missing, timedOut)) {
                assertEquals(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE, outcome.refusal());
                assertEquals(CompanionAdmission.PROVIDER_UNAVAILABLE_MESSAGE_KEY, outcome.messageKey());
            }
        }
    }
}

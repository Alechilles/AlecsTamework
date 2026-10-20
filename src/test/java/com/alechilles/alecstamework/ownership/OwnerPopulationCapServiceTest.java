package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests owner population-cap decision logic for tame-acquisition gates. */
class OwnerPopulationCapServiceTest {

    @Test
    void disabledCapAllowsAcquisition() {
        OwnerPopulationCapService.Decision decision = OwnerPopulationCapService.evaluateResolved(
                0,
                999,
                TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
        );

        assertTrue(decision.allowed());
        assertFalse(decision.capEnabled());
    }

    @Test
    void capReachedDeniesAcquisition() {
        OwnerPopulationCapService.Decision decision = OwnerPopulationCapService.evaluateResolved(
                5,
                5,
                TwGlobalConfig.PerPlayerLimitScope.GLOBAL
        );

        assertFalse(decision.allowed());
        assertTrue(decision.capEnabled());
        assertEquals(5, decision.limit());
        assertEquals(5, decision.currentCount());
        assertEquals(0, decision.remainingHeadroom());
        assertEquals("owner-cap-reached", decision.reason());
    }

    @Test
    void underCapAllowsWithHeadroom() {
        OwnerPopulationCapService.Decision decision = OwnerPopulationCapService.evaluateResolved(
                5,
                3,
                TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
        );

        assertTrue(decision.allowed());
        assertTrue(decision.capEnabled());
        assertEquals(2, decision.remainingHeadroom());
        assertEquals("owner-cap-allow", decision.reason());
    }

    @Test
    void nullScopeFallsBackToPerWorld() {
        OwnerPopulationCapService.Decision decision = OwnerPopulationCapService.evaluateResolved(5, 1, null);

        assertEquals(TwGlobalConfig.PerPlayerLimitScope.PER_WORLD, decision.scope());
    }

    @Test
    void unavailableAuthorityFailsClosedWithoutReportingFalseZero() {
        OwnerPopulationCapService.Decision decision =
                OwnerPopulationCapService.Decision.denyUnavailable(
                        5,
                        TwGlobalConfig.PerPlayerLimitScope.GLOBAL,
                        "owner-population-reconciling"
                );

        assertFalse(decision.allowed());
        assertTrue(decision.capEnabled());
        assertEquals(-1, decision.currentCount());
        assertEquals(0, decision.remainingHeadroom());
        assertEquals(
                "owner-population-reconciling",
                decision.reason()
        );
    }

    @Test
    void indexCountHonorsScopeAndCountsUnloadedCompanions() {
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000731");
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
        index.insert(record(ownerId, CompanionLocation.live("alpha", 0, 0, 0), "alpha"));
        index.insert(record(ownerId, CompanionLocation.item(), "beta"));
        index.insert(record(ownerId, CompanionLocation.released(null), "alpha"));
        index.insert(record(UUID.randomUUID(), CompanionLocation.live("alpha", 0, 0, 0), "alpha"));
        CompanionQueries queries = new CompanionQueries(index, new LoadedBodies<>());

        assertEquals(1, OwnerPopulationCapService.countOwnedPopulation(
                queries, TwGlobalConfig.PerPlayerLimitScope.PER_WORLD, "alpha", ownerId));
        assertEquals(1, OwnerPopulationCapService.countOwnedPopulation(
                queries, TwGlobalConfig.PerPlayerLimitScope.PER_WORLD, "beta", ownerId),
                "an item counts in the world it was tamed in");
        assertEquals(2, OwnerPopulationCapService.countOwnedPopulation(
                queries, TwGlobalConfig.PerPlayerLimitScope.GLOBAL, null, ownerId));
    }

    /** A role-aware pre-check maps owned and group refusals to the reasons that pick the player message. */
    @Test
    void preCheckRefusalsKeepTheirMessageReasons() {
        UUID ownerId = UUID.randomUUID();
        List<CompanionRecord> owned = List.of(record(ownerId, CompanionLocation.live("alpha", 0, 0, 0), "alpha"));
        CompanionRecord candidate = record(ownerId, CompanionLocation.live("alpha", 0, 0, 0), "alpha");
        PopulationGroupPolicy herd = new PopulationGroupPolicy("herd", PopulationGroupScope.GLOBAL, 1, 0, 1L);
        CompanionAdmission.Rules groupFull = new CompanionAdmission.Rules(5, false, role -> List.of(herd));
        CompanionAdmission.Rules ownedFull = new CompanionAdmission.Rules(1, false, role -> List.of());
        CompanionAdmission.Rules roomy = new CompanionAdmission.Rules(5, false, role -> List.of());

        OwnerPopulationCapService.Decision group = OwnerPopulationCapService.fromPrecheck(
                denial(CompanionAdmission.check(owned, null, candidate, groupFull, CompanionAdmission.Provided.none())),
                groupFull);
        OwnerPopulationCapService.Decision ownedLimit = OwnerPopulationCapService.fromPrecheck(
                denial(CompanionAdmission.check(owned, null, candidate, ownedFull, CompanionAdmission.Provided.none())),
                ownedFull);
        OwnerPopulationCapService.Decision allowed = OwnerPopulationCapService.fromPrecheck(
                denial(CompanionAdmission.check(owned, null, candidate, roomy, CompanionAdmission.Provided.none())),
                roomy);

        assertFalse(group.allowed());
        assertEquals(OwnerPopulationCapService.REASON_GROUP_CAP, group.reason());
        assertEquals("tamework.ui.population.groupLimit", OwnerMessageUtil.acquisitionDeniedKey(group));
        assertFalse(ownedLimit.allowed());
        assertEquals("owner-cap-reached", ownedLimit.reason());
        assertEquals("tamework.ui.population.ownedLimit", OwnerMessageUtil.acquisitionDeniedKey(ownedLimit));
        assertTrue(allowed.allowed());
    }

    /** A tamed spawn or a tame is refused by the deployed limit with its own reason, limit and message. */
    @Test
    void aDeployedLimitRefusalHasItsOwnReasonLimitAndMessage() {
        UUID ownerId = UUID.randomUUID();
        List<CompanionRecord> owned = List.of(record(ownerId, CompanionLocation.live("alpha", 1, 64, 1), "alpha"));
        CompanionRecord candidate = record(ownerId, CompanionLocation.live("alpha", 2, 64, 2), "alpha");
        CompanionAdmission.Rules rules = new CompanionAdmission.Rules(9, 1, false, role -> List.of());

        OwnerPopulationCapService.Decision decision = OwnerPopulationCapService.fromPrecheck(
                denial(CompanionAdmission.check(owned, null, candidate, rules, CompanionAdmission.Provided.none())),
                rules);

        assertFalse(decision.allowed());
        assertEquals(OwnerPopulationCapService.REASON_DEPLOYED_CAP, decision.reason());
        assertEquals(1, decision.limit());
        assertEquals(CompanionAdmission.DEPLOYED_LIMIT_MESSAGE_KEY, OwnerMessageUtil.acquisitionDeniedKey(decision));
    }

    /** A provider refusal is not a group limit: it has its own reason and shows its own message. */
    @Test
    void providerRefusalsHaveTheirOwnReasonAndShowTheirOwnMessage() {
        CompanionAdmission.Rules rules = new CompanionAdmission.Rules(5, false, role -> List.of());

        OwnerPopulationCapService.Decision denied = OwnerPopulationCapService.fromPrecheck(
                new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_DENIED,
                        "runeteria.husbandry.locked"), rules);
        OwnerPopulationCapService.Decision checking = OwnerPopulationCapService.fromPrecheck(
                new CompanionAdmissionGate.Denial(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE,
                        "tamework.ui.population.checkingRequirements"), rules);
        OwnerPopulationCapService.Decision unavailable = OwnerPopulationCapService.fromPrecheck(
                CompanionAdmissionGate.Denial.of(CompanionAdmission.Refusal.PROVIDER_UNAVAILABLE), rules);

        assertFalse(denied.allowed());
        assertEquals(OwnerPopulationCapService.REASON_PROVIDER_DENIED, denied.reason());
        assertEquals("runeteria.husbandry.locked", OwnerMessageUtil.acquisitionDeniedKey(denied));
        assertEquals(OwnerPopulationCapService.REASON_PROVIDER_UNAVAILABLE, checking.reason());
        assertEquals("tamework.ui.population.checkingRequirements", OwnerMessageUtil.acquisitionDeniedKey(checking));
        assertEquals("tamework.ui.population.providerUnavailable", OwnerMessageUtil.acquisitionDeniedKey(unavailable));
    }

    private static CompanionAdmissionGate.Denial denial(CompanionAdmission.Refusal refusal) {
        return refusal == null ? null : CompanionAdmissionGate.Denial.of(refusal);
    }

    private static CompanionRecord record(UUID owner, CompanionLocation at, String homeWorld) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", at).ownerUuid(owner).homeWorld(homeWorld).build();
    }

}

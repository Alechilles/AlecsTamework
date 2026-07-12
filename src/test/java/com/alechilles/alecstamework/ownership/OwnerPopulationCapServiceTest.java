package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
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
    void perWorldLegacyCountWithoutWorldContextReturnsConservativeSentinel() {
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000731");
        OwnerPopulationIndex index = new OwnerPopulationIndex();
        index.replaceCommittedEntries(List.of(
                new OwnerPopulationEntry(
                        "profile-a",
                        ownerId,
                        "alpha",
                        CompanionLifecycleState.ACTIVE,
                        1L
                )
        ), OwnerPopulationReadiness.READY);

        assertEquals(
                Integer.MAX_VALUE,
                OwnerPopulationCapService.countOwnedPopulation(
                        index,
                        OwnerPopulationLimitScope.PER_WORLD,
                        null,
                        ownerId
                )
        );
        assertEquals(
                1,
                OwnerPopulationCapService.countOwnedPopulation(
                        index,
                        OwnerPopulationLimitScope.GLOBAL,
                        null,
                        ownerId
                )
        );
    }
}

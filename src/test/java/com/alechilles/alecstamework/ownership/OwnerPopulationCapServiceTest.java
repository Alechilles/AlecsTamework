package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void foreignThreadSourcesUseDeferredCounting() {
        AtomicBoolean foreignDirectCalled = new AtomicBoolean(false);
        OwnerPopulationCapService.PopulationSource currentThreadSource = new OwnerPopulationCapService.PopulationSource() {
            @Override
            public boolean isInCallingThread() {
                return true;
            }

            @Override
            public int countDirect() {
                return 2;
            }

            @Override
            public CompletableFuture<Integer> countDeferred() {
                return CompletableFuture.failedFuture(new AssertionError("current-thread source should not defer"));
            }
        };
        OwnerPopulationCapService.PopulationSource foreignThreadSource = new OwnerPopulationCapService.PopulationSource() {
            @Override
            public boolean isInCallingThread() {
                return false;
            }

            @Override
            public int countDirect() {
                foreignDirectCalled.set(true);
                throw new AssertionError("foreign-thread source should not count directly");
            }

            @Override
            public CompletableFuture<Integer> countDeferred() {
                return CompletableFuture.completedFuture(3);
            }
        };

        int count = OwnerPopulationCapService.countOwnedPopulationAcrossSources(
                List.of(currentThreadSource, foreignThreadSource)
        );

        assertEquals(5, count);
        assertFalse(foreignDirectCalled.get());
    }
}

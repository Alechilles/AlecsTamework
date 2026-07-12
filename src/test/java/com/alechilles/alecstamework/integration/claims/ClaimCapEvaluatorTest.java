package com.alechilles.alecstamework.integration.claims;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimCapEvaluatorTest {
    @Test
    void perChunkAndTotalRulesUseSmallestHeadroom() {
        ClaimCapEvaluator.Evaluation totalLimited = ClaimCapEvaluator.evaluate(2, 7, 5, 4, 1);
        ClaimCapEvaluator.Evaluation chunkLimited = ClaimCapEvaluator.evaluate(1, 20, 5, 3, 1);

        assertEquals(10L, totalLimited.perChunkCapacity());
        assertEquals(7L, totalLimited.effectiveCapacity());
        assertEquals(2L, totalLimited.remainingHeadroom());
        assertEquals(ClaimCapEvaluator.LimitingConstraint.TOTAL, totalLimited.limitingConstraint());
        assertEquals(5L, chunkLimited.effectiveCapacity());
        assertEquals(1L, chunkLimited.remainingHeadroom());
        assertEquals(ClaimCapEvaluator.LimitingConstraint.PER_CHUNK, chunkLimited.limitingConstraint());
    }

    @Test
    void perChunkRuleRequiresPositiveWorldScopedExtent() {
        ClaimCapEvaluator.Evaluation evaluation = ClaimCapEvaluator.evaluate(2, 20, 0, 0, 0);

        assertFalse(evaluation.valid());
        assertEquals("claim-footprint-required", evaluation.reason());
        assertFalse(evaluation.admits(1));
    }

    @Test
    void pendingCapacityParticipatesInAtomicHeadroomMath() {
        ClaimCapEvaluator.Evaluation evaluation = ClaimCapEvaluator.evaluate(0, 5, 0, 3, 1);

        assertTrue(evaluation.admits(1));
        assertFalse(evaluation.admits(2));
        assertEquals(1L, evaluation.remainingHeadroom());
    }

    @Test
    void disabledRulesReportUnboundedHeadroomWithoutInventingACap() {
        ClaimCapEvaluator.Evaluation evaluation = ClaimCapEvaluator.evaluate(0, 0, 0, 99, 77);

        assertFalse(evaluation.active());
        assertTrue(evaluation.valid());
        assertEquals(0L, evaluation.effectiveCapacity());
        assertEquals(Long.MAX_VALUE, evaluation.remainingHeadroom());
        assertTrue(evaluation.admits(Integer.MAX_VALUE));
    }
}

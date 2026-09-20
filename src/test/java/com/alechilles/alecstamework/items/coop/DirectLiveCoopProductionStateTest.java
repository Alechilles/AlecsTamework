package com.alechilles.alecstamework.items.coop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Regression coverage for the 2026-09-19 quarantined-checkpoint world-tick crash. */
class DirectLiveCoopProductionStateTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final ProfileId UNRELATED_PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000002"
    );

    @Test
    void admissionRejectionSuspendsOnlyTheRejectedProfileWithoutEscapingRecord() {
        AtomicInteger submissions = new AtomicInteger();
        DirectLiveCoopProductionState state = new DirectLiveCoopProductionState(
                null,
                (operationId, idempotencyKey, mutation) -> {
                    submissions.incrementAndGet();
                    throw new IllegalStateException("persistence_mutation_not_admitted:quarantined");
                }
        );

        assertDoesNotThrow(() -> state.record(PROFILE, 12_000L, 29L));

        assertTrue(state.pending(PROFILE));
        assertFalse(state.pending(UNRELATED_PROFILE));
        assertDoesNotThrow(() -> state.record(PROFILE, 24_000L, 29L));
        assertEquals(1, submissions.get());
    }
}

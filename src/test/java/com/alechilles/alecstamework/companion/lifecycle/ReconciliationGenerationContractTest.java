package com.alechilles.alecstamework.companion.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for canonical reconciliation generation evidence. */
class ReconciliationGenerationContractTest {
    @Test
    void generationZeroIsValidAndAdvancesExactly() {
        assertEquals(0, ReconciliationGeneration.INITIAL.value());
        assertEquals(1, ReconciliationGeneration.INITIAL.next().value());
        assertThrows(IllegalArgumentException.class,
                () -> new ReconciliationGeneration(-1));
    }

    @Test
    void generationNeverWraps() {
        assertThrows(IllegalStateException.class,
                () -> new ReconciliationGeneration(Long.MAX_VALUE).next());
    }
}

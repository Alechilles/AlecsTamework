package com.alechilles.alecstamework.persistence.operation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Replacement evidence that generation zero is data, not an unset sentinel. */
class OperationGenerationContractTest {
    @Test
    void zeroIsValidAndOnlyNegativeGenerationsAreRejected() {
        assertEquals(0, OperationGeneration.INITIAL.value());
        assertEquals(1, OperationGeneration.INITIAL.next().value());
        assertThrows(IllegalArgumentException.class, () -> new OperationGeneration(-1));
    }

    @Test
    void generationCannotWrap() {
        assertThrows(IllegalStateException.class, () -> new OperationGeneration(Long.MAX_VALUE).next());
    }
}

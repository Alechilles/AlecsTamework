package com.alechilles.alecstamework.npc.breeding;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for canonical breeding-pair identity. */
class BreedingPairKeyTest {
    @Test
    void canonicalizesParentOrderWithinWorld() {
        UUID first = new UUID(0L, 10L);
        UUID second = new UUID(0L, 20L);

        BreedingPairKey forward = BreedingPairKey.of("world-a", first, second);
        BreedingPairKey reversed = BreedingPairKey.of("world-a", second, first);

        assertEquals(forward, reversed);
        assertEquals(forward, BreedingPairKey.of("  world-a  ", first, second));
        assertEquals(first, forward.firstParentUuid());
        assertEquals(second, forward.secondParentUuid());
        assertTrue(forward.contains(first));
        assertTrue(forward.contains(second));
    }

    @Test
    void keepsWorldIdentityInKey() {
        UUID first = new UUID(0L, 10L);
        UUID second = new UUID(0L, 20L);

        assertNotEquals(
                BreedingPairKey.of("world-a", first, second),
                BreedingPairKey.of("world-b", first, second)
        );
    }

    @Test
    void rejectsPairingEntityWithItself() {
        UUID parent = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> BreedingPairKey.of("world-a", parent, parent));
    }
}

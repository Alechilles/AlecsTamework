package com.alechilles.alecstamework.items.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the immutable spawner presentation handoff contract. */
class SpawnerPublishedEffectTest {
    @Test
    void normalizesOptionalAssetIdsWithoutChangingPosition() {
        SpawnerPublishedEffect effect = new SpawnerPublishedEffect(
                1.25,
                -2.5,
                3.75,
                "  Poof_Small ",
                " "
        );

        assertEquals(1.25, effect.x());
        assertEquals(-2.5, effect.y());
        assertEquals(3.75, effect.z());
        assertEquals("Poof_Small", effect.particleSystem());
        assertNull(effect.soundEvent());
    }

    @Test
    void rejectsNonFinitePositionEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpawnerPublishedEffect(
                        Double.NaN, 0.0, 0.0, null, null
                )
        );
    }
}

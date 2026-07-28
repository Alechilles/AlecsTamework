package com.alechilles.alecstamework.items.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.component.RemoveReason;
import org.junit.jupiter.api.Test;

class HytaleDormantCompanionObservationFactoryTest {
    @Test
    void destructiveRemovalIsTheOnlyAcceptedEntityRemovalReason() {
        assertTrue(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.REMOVE, false, false, false
                ));
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.UNLOAD, false, false, false
                ));
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.BUILDER_TOOLS_UNDO,
                        false,
                        false,
                        false
                ));
    }

    @Test
    void deathAndIntentionalRetirementOwnTheirRemovalBoundaries() {
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.REMOVE, true, false, false
                ));
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.REMOVE, false, true, false
                ));
        assertFalse(HytaleDormantCompanionObservationFactory
                .authoritativeRemoval(
                        RemoveReason.REMOVE, false, false, true
                ));
    }
}

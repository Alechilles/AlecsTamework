package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for passive-vs-manual breeding readiness selection. */
class BreedingReadinessPolicyTest {

    @Test
    void passivePolicyAcceptsPassiveReadyFlagOnly() {
        TameworkBreedingComponent breeding = new TameworkBreedingComponent();
        breeding.setReady(true);

        assertTrue(BreedingReadinessPolicy.passive(1000L).accepts(breeding));

        breeding.setReady(false);
        assertFalse(BreedingReadinessPolicy.passive(1000L).accepts(breeding));
    }

    @Test
    void manualPolicyIgnoresPassiveReadyFlag() {
        TameworkBreedingComponent breeding = new TameworkBreedingComponent();
        breeding.setReady(true);

        assertFalse(BreedingReadinessPolicy.manual(UUID.randomUUID(), 1000L).accepts(breeding));
    }

    @Test
    void manualPolicyRejectsNullPlayerEvenWhenPassiveReady() {
        TameworkBreedingComponent breeding = new TameworkBreedingComponent();
        breeding.setReady(true);

        assertFalse(BreedingReadinessPolicy.manual(null, 1000L).accepts(breeding));
    }

    @Test
    void manualPolicyRequiresSameUnexpiredPlayerSelection() {
        UUID playerUuid = UUID.randomUUID();
        TameworkBreedingComponent breeding = new TameworkBreedingComponent();
        breeding.markManualBreedingReady(playerUuid, 2000L);

        assertTrue(BreedingReadinessPolicy.manual(playerUuid, 1999L).accepts(breeding));
        assertFalse(BreedingReadinessPolicy.manual(UUID.randomUUID(), 1999L).accepts(breeding));
        assertFalse(BreedingReadinessPolicy.manual(playerUuid, 2000L).accepts(breeding));
    }

    @Test
    void manualPolicyDoesNotRequireBreedingEnabledToggle() {
        assertTrue(BreedingReadinessPolicy.passive(1000L).requiresBreedingEnabled());
        assertFalse(BreedingReadinessPolicy.manual(UUID.randomUUID(), 1000L).requiresBreedingEnabled());
    }
}

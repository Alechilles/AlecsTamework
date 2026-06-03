package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for final partner-readiness validation before spawning offspring. */
class BreedingOffspringServiceTest {

    @Test
    void passivePartnerReadinessRequiresPassiveReadyFlag() {
        TameworkBreedingComponent breeding = new TameworkBreedingComponent();

        assertFalse(BreedingOffspringService.acceptsPartnerReadiness(
                BreedingReadinessPolicy.passive(1000L),
                breeding
        ));

        breeding.setReady(true);
        assertTrue(BreedingOffspringService.acceptsPartnerReadiness(
                BreedingReadinessPolicy.passive(1000L),
                breeding
        ));
    }

    @Test
    void manualPartnerReadinessAcceptsManualSelectionWithoutPassiveReadyFlag() {
        UUID playerUuid = UUID.randomUUID();
        TameworkBreedingComponent breeding = new TameworkBreedingComponent();
        breeding.setReady(false);
        breeding.markManualBreedingReady(playerUuid, 2000L);

        assertTrue(BreedingOffspringService.acceptsPartnerReadiness(
                BreedingReadinessPolicy.manual(playerUuid, 1000L),
                breeding
        ));
    }
}

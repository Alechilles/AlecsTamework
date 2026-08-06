package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for forcing a parent ready after an earlier breeding cooldown. */
class BreedingCooldownResetServiceTest {
    @Test
    void readyResetClearsEveryComponentCooldownField() {
        TameworkBreedingComponent breeding = new TameworkBreedingComponent(
                "livestock", 100.0, 25L, false, true, 900L, UUID.randomUUID(), 100L, 800L
        );

        BreedingCooldownResetService.applyReadyState(breeding);

        assertTrue(breeding.isReady());
        assertEquals(0L, breeding.getCooldownUntilMs());
        assertEquals(0L, breeding.getCooldownStartedAtMs());
        assertEquals(0L, breeding.getCooldownDurationMs());
        assertNull(breeding.getLastPartnerUuid());
    }

}

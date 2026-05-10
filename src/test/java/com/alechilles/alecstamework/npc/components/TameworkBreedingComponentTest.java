package com.alechilles.alecstamework.npc.components;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests breeding component enable-toggle defaults and cloning behavior. */
class TameworkBreedingComponentTest {

    @Test
    void legacyConstructorDefaultsBreedingDisabled() {
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                "TestConfig",
                42.0,
                1234L,
                true,
                5678L,
                UUID.randomUUID()
        );

        assertFalse(component.isEnabled());
    }

    @Test
    void clonePreservesBreedingEnabledState() {
        long now = System.currentTimeMillis();
        long durationMs = 123456L;
        UUID manualPlayerUuid = UUID.randomUUID();
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                "TestConfig",
                42.0,
                1234L,
                false,
                true,
                now + durationMs,
                UUID.randomUUID(),
                now,
                durationMs,
                manualPlayerUuid,
                now + 5000L
        );

        TameworkBreedingComponent cloned = component.clone();

        assertTrue(cloned.isEnabled());
        assertEquals(now, cloned.getCooldownStartedAtMs());
        assertEquals(durationMs, cloned.getCooldownDurationMs());
        assertEquals(manualPlayerUuid, cloned.getManualBreedingPlayerUuid());
        assertEquals(now + 5000L, cloned.getManualBreedingUntilMs());
    }

    @Test
    void manualBreedingReadinessRequiresSamePlayerAndUnexpiredSelection() {
        TameworkBreedingComponent component = new TameworkBreedingComponent();
        UUID playerUuid = UUID.randomUUID();

        component.markManualBreedingReady(playerUuid, 2000L);

        assertTrue(component.isManualBreedingReadyFor(playerUuid, 1999L));
        assertFalse(component.isManualBreedingReadyFor(UUID.randomUUID(), 1999L));
        assertFalse(component.isManualBreedingReadyFor(playerUuid, 2000L));
    }

    @Test
    void clearManualBreedingReadinessRemovesSelection() {
        TameworkBreedingComponent component = new TameworkBreedingComponent();
        UUID playerUuid = UUID.randomUUID();
        component.markManualBreedingReady(playerUuid, 2000L);

        component.clearManualBreedingReady();

        assertFalse(component.isManualBreedingReadyFor(playerUuid, 1000L));
        assertEquals(0L, component.getManualBreedingUntilMs());
    }
}

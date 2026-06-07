package com.alechilles.alecstamework.npc.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests durable harvest cooldown component behavior. */
class TameworkHarvestCooldownComponentTest {

    @Test
    void activeCooldownSupportsNegativeGameTimeEpochs() {
        TameworkHarvestCooldownComponent component = new TameworkHarvestCooldownComponent();
        component.setCooldownUntilMs(-1000L);

        assertTrue(component.isCooldownActive(-2000L));
        assertFalse(component.isCooldownActive(-1000L));
        assertFalse(component.isCooldownActive(0L));
    }

    @Test
    void clonePreservesCooldownWindow() {
        TameworkHarvestCooldownComponent component = new TameworkHarvestCooldownComponent();
        component.setCooldownStartedAtMs(-5000L);
        component.setCooldownDurationMs(4000L);
        component.setCooldownUntilMs(-1000L);

        TameworkHarvestCooldownComponent cloned = component.clone();

        assertEquals(-5000L, cloned.getCooldownStartedAtMs());
        assertEquals(4000L, cloned.getCooldownDurationMs());
        assertEquals(-1000L, cloned.getCooldownUntilMs());
    }
}

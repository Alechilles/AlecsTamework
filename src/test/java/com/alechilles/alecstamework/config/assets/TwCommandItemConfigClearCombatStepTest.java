package com.alechilles.alecstamework.config.assets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests defaults for the ClearCombat command step. */
class TwCommandItemConfigClearCombatStepTest {

    @Test
    void clearCombatStepDefaultsAreSafeForImmediateRecall() {
        TwCommandItemConfig.ClearCombatStep step = new TwCommandItemConfig.ClearCombatStep();

        assertEquals("Idle", step.getState());
        assertNull(step.getSubState());
        assertArrayEquals(new String[] { "LockedTarget" }, step.getTargetSlots());
        assertTrue(step.isAssignOwnerAsMasterTarget());
    }
}

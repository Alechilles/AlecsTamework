package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for companions retaining their owner in hostile target memory after accidental hits. */
class ActionTameworkForgetHostileTargetTest {
    @Test
    void removesMatchingHostileMemoryEntry() {
        TargetMemory memory = new TargetMemory(30.0f);
        memory.getKnownHostiles().put(42, 30.0f);

        assertTrue(ActionTameworkForgetHostileTarget.removeHostileTarget(memory, 42));
        assertFalse(memory.getKnownHostiles().containsKey(42));
    }

    @Test
    void absentOrInvalidTargetDoesNotReportRemoval() {
        TargetMemory memory = new TargetMemory(30.0f);

        assertFalse(ActionTameworkForgetHostileTarget.removeHostileTarget(memory, 42));
        assertFalse(ActionTameworkForgetHostileTarget.removeHostileTarget(memory, -1));
        assertFalse(ActionTameworkForgetHostileTarget.removeHostileTarget(null, 42));
    }
}

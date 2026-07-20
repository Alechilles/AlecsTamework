package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void builderIsRegistered() throws Exception {
        String registrar = Files.readString(
                Path.of("src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(registrar.contains("BuilderActionTameworkForgetHostileTarget.BUILDER_ID"));
        assertTrue(registrar.contains("BuilderActionTameworkForgetHostileTarget::new"));
    }
}

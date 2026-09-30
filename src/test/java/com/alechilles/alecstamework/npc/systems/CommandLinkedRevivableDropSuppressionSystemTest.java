package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedRevivableDropSuppressionSystemTest {

    @Test
    void suppressesDropsForACompanionBodyWhenDeadRespawnIsEnabled() {
        TameworkCompanionComponent stamp = new TameworkCompanionComponent(UUID.randomUUID(), 0L);

        assertTrue(CommandLinkedRevivableDropSuppressionSystem.shouldSuppressDrops(stamp, true));
    }

    @Test
    void doesNotSuppressDropsWhenDeadRespawnIsDisabled() {
        TameworkCompanionComponent stamp = new TameworkCompanionComponent(UUID.randomUUID(), 0L);

        assertFalse(CommandLinkedRevivableDropSuppressionSystem.shouldSuppressDrops(stamp, false));
    }
}

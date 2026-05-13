package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.AddReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionProgressionBootstrapOnLoadSystemTest {
    @Test
    void queuesAttachmentRepairForLoadedUntamedNpcWithMigrationConfig() {
        assertTrue(CompanionProgressionBootstrapOnLoadSystem.shouldRunAttachmentLoadBootstrap(
                AddReason.LOAD,
                null,
                false,
                true
        ));
    }

    @Test
    void queuesAttachmentRepairForLoadedUntamedNpcWithStoredAttachments() {
        assertTrue(CompanionProgressionBootstrapOnLoadSystem.shouldRunAttachmentLoadBootstrap(
                AddReason.LOAD,
                null,
                true,
                false
        ));
    }

    @Test
    void doesNotQueueAttachmentRepairForFreshSpawnWithoutStoredOrMigrationState() {
        assertFalse(CompanionProgressionBootstrapOnLoadSystem.shouldRunAttachmentLoadBootstrap(
                AddReason.SPAWN,
                new TameworkTamedComponent(true),
                false,
                true
        ));
    }
}

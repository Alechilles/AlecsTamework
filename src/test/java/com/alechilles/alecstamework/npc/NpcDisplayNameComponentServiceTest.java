package com.alechilles.alecstamework.npc;

import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentDisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NpcDisplayNameComponentServiceTest {
    @Test
    void createPersistentNameUsesPersistentDisplayComponent() {
        PersistentDisplayName displayName = NpcDisplayNameComponentService.createPersistent("Betsy");

        assertNotNull(displayName.getDisplayName());
        assertEquals("Betsy", displayName.getDisplayName().getAnsiMessage());
    }

    @Test
    void createRuntimeNameUsesRuntimeDisplayComponent() {
        DisplayNameComponent displayName = NpcDisplayNameComponentService.createRuntime("Betsy");

        assertNotNull(displayName.getDisplayName());
        assertEquals("Betsy", displayName.getDisplayName().getAnsiMessage());
    }
}

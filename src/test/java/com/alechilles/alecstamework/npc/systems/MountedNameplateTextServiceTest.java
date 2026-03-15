package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MountedNameplateTextServiceTest {
    private final MountedNameplateTextService service = new MountedNameplateTextService();

    @Test
    void hideNameplateClearsExistingText() {
        Nameplate nameplate = new Nameplate("Betsy");

        service.hideNameplate(nameplate);

        assertEquals("", nameplate.getText());
    }

    @Test
    void restoreNameplateUsesProvidedText() {
        Nameplate nameplate = new Nameplate();

        service.restoreNameplate(nameplate, "Betsy");

        assertEquals("Betsy", nameplate.getText());
    }

    @Test
    void createDisplayNameComponentNormalizesNullToEmptyMessage() {
        DisplayNameComponent displayName = service.createDisplayNameComponent(null);

        assertNotNull(displayName.getDisplayName());
        assertEquals("", displayName.getDisplayName().getAnsiMessage());
    }
}

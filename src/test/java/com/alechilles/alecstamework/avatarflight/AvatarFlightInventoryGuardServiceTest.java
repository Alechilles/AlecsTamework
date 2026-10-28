package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.server.core.event.events.ecs.InventoryActiveSlotRequestEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightInventoryGuardServiceTest {
    @Test
    void auxiliaryClientSelectionChangesAreLockedWhileServerRestorationRemainsAllowed() {
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.setLockedHotbarSlot(3);

        assertTrue(AvatarFlightInventoryGuardSystem.shouldCancel(flight,
                request(InventoryComponent.UTILITY_SECTION_ID, false)));
        assertTrue(AvatarFlightInventoryGuardSystem.shouldCancel(flight,
                request(InventoryComponent.TOOLS_SECTION_ID, false)));
        assertFalse(AvatarFlightInventoryGuardSystem.shouldCancel(flight,
                request(InventoryComponent.HOTBAR_SECTION_ID, false)));
        assertFalse(AvatarFlightInventoryGuardSystem.shouldCancel(flight,
                request(InventoryComponent.UTILITY_SECTION_ID, true)));
    }

    @Test
    void hotbarChangesRestoreTheLockedSlot() {
        AvatarFlightComponent flight = new AvatarFlightComponent();
        flight.setLockedHotbarSlot(3);

        assertTrue(AvatarFlightHotbarGuardSystem.shouldRestore(flight,
                new InventorySetActiveSlotEvent(InventoryComponent.HOTBAR_SECTION_ID, 3, (byte) 4)));
        assertFalse(AvatarFlightHotbarGuardSystem.shouldRestore(flight,
                new InventorySetActiveSlotEvent(InventoryComponent.HOTBAR_SECTION_ID, 4, (byte) 3)));
    }

    private static InventoryActiveSlotRequestEvent request(int sectionId, boolean serverRequest) {
        return new InventoryActiveSlotRequestEvent(sectionId, 3, (byte) 4, serverRequest);
    }

}

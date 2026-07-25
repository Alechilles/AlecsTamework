package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import org.junit.jupiter.api.Test;

/** Guards the exact built-in inventory coordinates eligible for durable coop intake. */
final class HytaleCapturedItemCoopInteractionServiceTest {

    @Test
    void mapsOnlyDurablyAddressableInventorySections() {
        assertEquals(
                CoopCapturedItemInventoryPosition.Section.HOTBAR,
                HytaleCapturedItemCoopInteractionService
                        .sectionForEvidence(
                                InventoryComponent.HOTBAR_SECTION_ID
                        )
        );
        assertEquals(
                CoopCapturedItemInventoryPosition.Section.STORAGE,
                HytaleCapturedItemCoopInteractionService
                        .sectionForEvidence(
                                InventoryComponent.STORAGE_SECTION_ID
                        )
        );
        assertEquals(
                CoopCapturedItemInventoryPosition.Section.BACKPACK,
                HytaleCapturedItemCoopInteractionService
                        .sectionForEvidence(
                                InventoryComponent.BACKPACK_SECTION_ID
                        )
        );
        assertNull(
                HytaleCapturedItemCoopInteractionService
                        .sectionForEvidence(
                                InventoryComponent.UTILITY_SECTION_ID
                        )
        );
        assertNull(
                HytaleCapturedItemCoopInteractionService
                        .sectionForEvidence(
                                InventoryComponent.TOOLS_SECTION_ID
                        )
        );
        assertNull(
                HytaleCapturedItemCoopInteractionService
                        .sectionForEvidence(0)
        );
    }
}

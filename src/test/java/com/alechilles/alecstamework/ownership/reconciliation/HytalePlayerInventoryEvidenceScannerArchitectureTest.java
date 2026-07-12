package com.alechilles.alecstamework.ownership.reconciliation;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HytalePlayerInventoryEvidenceScannerArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/ownership/reconciliation/"
                    + "HytalePlayerInventoryEvidenceScanner.java"
    );

    @Test
    void updateFiveInventoryCoverageIncludesAllSixPersistedSectionsIncludingTool() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("InventoryComponent.Storage.getComponentType()"));
        assertTrue(source.contains("InventoryComponent.Armor.getComponentType()"));
        assertTrue(source.contains("InventoryComponent.Hotbar.getComponentType()"));
        assertTrue(source.contains("InventoryComponent.Utility.getComponentType()"));
        assertTrue(source.contains("InventoryComponent.Tool.getComponentType()"));
        assertTrue(source.contains("InventoryComponent.Backpack.getComponentType()"));
        assertFalse(source.contains("InventoryComponent.EVERYTHING"),
                "Hytale 0.5.6 EVERYTHING omits the Tool section and is not complete-save coverage.");
    }
}

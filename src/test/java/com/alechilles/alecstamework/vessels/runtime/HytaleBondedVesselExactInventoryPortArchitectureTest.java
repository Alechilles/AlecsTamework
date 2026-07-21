package com.alechilles.alecstamework.vessels.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HytaleBondedVesselExactInventoryPortArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/vessels/runtime/"
                    + "HytaleBondedVesselExactInventoryPort.java");

    @Test
    void exactAuthorityUsesStableLocationAndWorldThreadMetadataCas() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("LeaseBoundWorldDispatcher.execute(world"));
        assertTrue(source.contains("world.getEntityRef(holder)"));
        assertTrue(source.contains("InventoryComponent.Hotbar.getComponentType()"));
        assertTrue(source.contains("replaceItemStackInSlot("));
        assertTrue(source.contains("current.equals(transaction.getSlotBefore())"));
        assertTrue(source.contains("target.equals(transaction.getSlotAfter())"));
        assertFalse(source.contains("getActiveItem()"));
        assertFalse(source.contains("forEach("));
    }

    @Test
    void durableGenerationAndNonstackableGuardsFenceEngineCas() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("VESSEL_GENERATION, Codec.LONG"));
        assertTrue(source.contains("metadata.metadata().generation()"));
        assertTrue(source.contains("stack.getQuantity() == 1"));
        assertTrue(source.contains("item.getMaxStack() == 1"));
        assertTrue(source.contains("target-fingerprint-invalid"));
    }
}

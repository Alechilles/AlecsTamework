package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionProjectionPlacementArchitectureTest {
    private static final Path ROOT = Path.of("src/main/java/com/alechilles/alecstamework/items");

    @Test
    void apiProjectionPortsUseSharedActorRelativePlacement() throws Exception {
        String bonded = Files.readString(ROOT.resolve("HytaleBondedVesselWorldProjectionPort.java"));
        String provisioned = Files.readString(
                ROOT.resolve("HytaleProvisionedCompanionProjectionPort.java"));

        assertTrue(bonded.contains("spawnPosition.resolve("));
        assertTrue(provisioned.contains("spawnPosition.resolve("));
        assertFalse(bonded.contains("int local = ChunkUtil.SIZE / 2"));
        assertFalse(provisioned.contains("int local = ChunkUtil.SIZE / 2"));
    }
}

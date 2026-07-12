package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the split between spawn lifecycle orchestration and canonical-source planning. */
class CompanionSpawnAdmissionSizeArchitectureTest {
    private static final Path OWNERSHIP_ROOT = Path.of(
            "src/main/java/com/alechilles/alecstamework/ownership"
    );

    @Test
    void spawnAdmissionClassesRemainFocused() throws IOException {
        assertLineLimit("CompanionSpawnPopulationAdmissionService.java", 500);
        assertLineLimit("CompanionSpawnAdmissionPlanner.java", 500);
    }

    private static void assertLineLimit(String relativePath, int limit) throws IOException {
        int lines = Files.readAllLines(OWNERSHIP_ROOT.resolve(relativePath)).size();
        assertTrue(
                lines <= limit,
                () -> relativePath + " has " + lines + " lines; limit is " + limit
        );
    }
}

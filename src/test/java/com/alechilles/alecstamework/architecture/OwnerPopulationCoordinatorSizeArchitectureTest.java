package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the focused owner-population admission and compensation coordinators. */
class OwnerPopulationCoordinatorSizeArchitectureTest {
    private static final Path OWNERSHIP_ROOT = Path.of(
            "src/main/java/com/alechilles/alecstamework/ownership"
    );

    @Test
    void admissionAndCompensationCoordinatorsRemainFocused() throws IOException {
        assertLineLimit("OwnerPopulationAdmissionCoordinator.java", 500);
        assertLineLimit("OwnerPopulationJournalCloseCoordinator.java", 500);
        assertLineLimit("OwnerPopulationCompensationCoordinator.java", 500);
        assertLineLimit("OwnerMutationCompensationService.java", 500);
    }

    private static void assertLineLimit(String relativePath, int limit) throws IOException {
        Path path = OWNERSHIP_ROOT.resolve(relativePath);
        int lines = Files.readAllLines(path).size();
        assertTrue(lines <= limit, () -> relativePath + " has " + lines + " lines; limit is " + limit);
    }
}

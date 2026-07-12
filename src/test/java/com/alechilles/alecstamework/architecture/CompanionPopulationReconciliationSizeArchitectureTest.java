package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards reconciliation orchestration and coverage publication against regrowth. */
class CompanionPopulationReconciliationSizeArchitectureTest {
    private static final Path RECONCILIATION_ROOT = Path.of(
            "src/main/java/com/alechilles/alecstamework/ownership/reconciliation"
    );

    @Test
    void serviceAndCoveragePublisherRemainFocused() throws IOException {
        assertLineLimit("CompanionPopulationReconciliationService.java", 500);
        assertLineLimit("CompanionPopulationCoveragePublisher.java", 500);
    }

    private static void assertLineLimit(String relativePath, int limit) throws IOException {
        Path path = RECONCILIATION_ROOT.resolve(relativePath);
        int lines = Files.readAllLines(path).size();
        assertTrue(lines <= limit, () -> relativePath + " has " + lines + " lines; limit is " + limit);
    }
}

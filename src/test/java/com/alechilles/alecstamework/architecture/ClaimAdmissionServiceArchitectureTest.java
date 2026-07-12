package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the admission orchestrator against absorbing reservation-ledger responsibilities again. */
class ClaimAdmissionServiceArchitectureTest {
    private static final int MAX_RAW_LINES = 500;
    private static final Path SERVICE_SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/integration/claims/ClaimAdmissionService.java"
    );

    @Test
    void claimAdmissionServiceRemainsWithinItsRawLineBudget() throws IOException {
        int rawLines = Files.readAllLines(SERVICE_SOURCE).size();

        assertTrue(
                rawLines <= MAX_RAW_LINES,
                () -> SERVICE_SOURCE + " has " + rawLines + " raw lines; limit is " + MAX_RAW_LINES
        );
    }
}

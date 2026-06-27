package com.alechilles.alecstamework.interactions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TameworkClearFeedTroughWaterInteractionTimingTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/interactions/TameworkClearFeedTroughWaterInteraction.java"
    );

    @Test
    void clearWaterWaitsForConfiguredInteractionRuntime() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int runtimeGuard = source.indexOf("time < getRunTime()");
        int clearCall = source.indexOf("FeedTroughWaterStateService.clearStoredCharges");

        assertTrue(runtimeGuard >= 0, "Clear-water interactions must honor the configured RunTime before mutating trough water.");
        assertTrue(clearCall >= 0, "Clear-water interactions must still clear stored trough water after the hold completes.");
        assertTrue(runtimeGuard < clearCall, "The runtime guard must run before the trough water clear side effect.");
    }
}

package com.alechilles.alecstamework.architecture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the single terminal-owner split for prepared breeding handoff failures. */
class BreedingPreparedHandoffOwnershipArchitectureTest {
    private static final Path HANDOFF = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "npc", "actions", "BreedingPreparedPairingHandoffService.java"
    );

    @Test
    void worldFinalizationCancelsThroughRegistryOrRawBatchButNeverBoth() throws Exception {
        String source = Files.readString(HANDOFF, StandardCharsets.UTF_8);
        String method = between(source, "private void finalizeOnWorld(", "private void cancelLocal(");

        assertTrue(method.contains("boolean registryOwned = services.preparedPopulationRegistry()"));
        assertTrue(method.contains(".cancelOwnedJob("));
        assertTrue(method.contains("if (!registryOwned) {"));
        assertEquals(1, occurrences(method, "cancelPrepared(populationService, batch, reason);"));
        assertEquals(1, occurrences(method, ".cancelOwnedJob("));
        assertFalse(method.contains("cancelRemaining(jobId, reason)"));
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0, "Expected method start was not found");
        assertTrue(endIndex > startIndex, "Expected method end was not found");
        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}

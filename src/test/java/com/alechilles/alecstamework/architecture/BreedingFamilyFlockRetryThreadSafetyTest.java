package com.alechilles.alecstamework.architecture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards stable-ID capture and world-thread entity resolution for delayed flock retries. */
class BreedingFamilyFlockRetryThreadSafetyTest {
    @Test
    void delayedRetryCapturesUuidsAndResolvesFreshRefsInsideWorldExecute() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "npc",
                "actions", "BreedingFamilyFlockRetryService.java"
        ), StandardCharsets.UTF_8);

        assertTrue(source.contains("UUID childUuid = entityUuid(childRef, store)"));
        assertTrue(source.contains("() -> world.execute(() -> retry("));
        assertTrue(source.contains("world.getEntityRef(childUuid)"));
        assertTrue(source.contains("world.getEntityRef(parentAUuid)"));
        assertTrue(source.contains("world.getEntityRef(parentBUuid)"));
        assertFalse(source.contains("retry(world, childRef"));
        assertFalse(source.contains("retry(world, parentARef"));
    }
}

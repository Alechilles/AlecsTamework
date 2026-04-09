package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces safe needs-damage dispatch from runtime tick paths.
 */
class NeedsDamageDispatchGuardTest {
    private static final Path NEEDS_SERVICE_PATH = Paths.get(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "npc",
            "progression",
            "CompanionNeedsService.java"
    );

    @Test
    void needsSystemPathPassesCommandBufferIntoDamageDispatch() throws IOException {
        String content = Files.readString(NEEDS_SERVICE_PATH, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("applyNeedsDamage(\n                npcRef,\n                store,\n                commandBuffer,"),
                "CompanionNeedsService must pass commandBuffer into applyNeedsDamage from runNeedsUpdate."
        );
        assertTrue(
                content.contains("commandBuffer.run(bufferStore ->"),
                "Command-buffer damage path must defer execution through commandBuffer.run(...)."
        );
        assertTrue(
                content.contains("DamageSystems.executeDamage(npcRef, bufferStore, deferredDamage);"),
                "Deferred needs damage must execute against bufferStore inside commandBuffer.run(...)."
        );
    }
}

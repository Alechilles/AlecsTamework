package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Regression coverage for HyDragon's targetless physical-touchdown instruction. */
class BuilderActionTameworkConfirmLandingTest {
    @Test
    void landingConfirmationDoesNotRequireAnUnrelatedSensorTarget() throws Exception {
        String builder = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions/"
                        + "BuilderActionTameworkConfirmLanding.java"), StandardCharsets.UTF_8);

        assertFalse(builder.contains("requireFeature("),
                "landing confirmation operates on the executing NPC and must load without a player/NPC target");
        assertTrue(builder.contains("public BuilderActionTameworkConfirmLanding readConfig(JsonElement element)"),
                "keep the covariant readConfig method so already-compiled downstream mods remain compatible");
    }
}

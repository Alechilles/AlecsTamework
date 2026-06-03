package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionLevelingServiceSetLevelTest {
    private static final Path SERVICE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "npc",
            "progression",
            "CompanionLevelingService.java"
    );

    @Test
    void setLevelClampsToConfigWritesLevelXpFloorAndRefreshesModifiers() throws IOException {
        String content = Files.readString(SERVICE, StandardCharsets.UTF_8);
        int methodStart = content.indexOf("public static SetLevelResult setLevel");
        int methodEnd = content.indexOf("private static boolean isXpEligibleCompanion", methodStart);

        assertTrue(methodStart >= 0, "Companion leveling service should expose a setLevel method.");
        assertTrue(methodEnd > methodStart, "setLevel method should be bounded before XP eligibility helpers.");

        String method = content.substring(methodStart, methodEnd);
        assertTrue(
                method.contains("Math.max(1, Math.min(requestedLevel, maxLevel))"),
                "Set level should clamp requested level into the config's valid range."
        );
        assertTrue(
                method.contains("resolveCumulativeXpForLevel(config, appliedLevel)"),
                "Set level should place total XP at the target level floor."
        );
        assertTrue(
                method.contains("new TameworkLevelingComponent(config.getId(), appliedLevel, 0.0, totalXp)"),
                "Set level should reset current-level XP after moving to the level floor."
        );
        assertTrue(
                method.contains("store.putComponent(npcRef, type, updated)"),
                "Set level should persist the updated leveling component."
        );
        assertTrue(
                method.contains("applyTraitModifiers(npcRef, store, null)"),
                "Set level should immediately reapply progression stat modifiers."
        );
    }
}

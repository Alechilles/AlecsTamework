package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TalentUtilityEffectContractTest {
    @Test
    void needsDecayUsesCompanionProgressionMultiplier() throws IOException {
        String content = readSource("npc", "progression", "CompanionNeedsService.java");
        assertTrue(content.contains("NEEDS_DECAY_MULTIPLIER_EFFECT_KEY = \"NeedsDecayMultiplier\""));
        assertTrue(content.contains("resolveNeedsDecayMultiplier(npcRef, store)"));
        assertTrue(content.contains("decay.getHungerPerMinute() * elapsedMinutes * needsDecayMultiplier"));
        assertTrue(content.contains("decay.getThirstPerMinute() * elapsedMinutes * needsDecayMultiplier"));
    }

    @Test
    void freeRestorationCooldownFreezesLiveTalentMultiplier() throws IOException {
        String observation = readSource(
                "items", "persistence",
                "HytaleDormantCompanionObservationFactory.java"
        );
        assertTrue(observation.contains("REVIVE_COOLDOWN_MULTIPLIER"));
        assertTrue(observation.contains("\"ReviveCooldownMultiplier\""));
        assertTrue(observation.contains(
                "CompanionProgressionModifierService.resolveMultiplier("
        ));
        assertTrue(observation.contains(
                "long cooldown = reviveCooldownMs(reference, store, roleId)"
        ));
        assertTrue(observation.contains(
                "saturatingAdd(diedAtMs, cooldown)"
        ));

        String author = readSource(
                "items", "persistence", "PositiveEvidenceDormantAuthor.java"
        );
        assertTrue(author.contains(
                "death.restorationAvailableAtMs()"
        ));
    }

    @Test
    void progressionBreakdownPrioritizesNewUtilityEffectKeys() throws IOException {
        String content = readSource("npc", "progression", "CompanionProgressionModifierBreakdownService.java");
        assertTrue(content.contains("\"NeedsDecayMultiplier\""));
        assertTrue(content.contains("\"ReviveCooldownMultiplier\""));
        assertTrue(content.contains("\"TraitMutationChanceMultiplier\""));
        assertTrue(content.contains("\"AppearanceMutationChanceMultiplier\""));
        assertTrue(content.contains("\"HarvestCooldownMultiplier\""));
    }

    @Test
    void appearanceMutationUsesParentTalentMultipliers() throws IOException {
        String breedingContent = readSource("npc", "actions", "BreedingOffspringProgressionService.java");
        assertTrue(breedingContent.contains(
                "APPEARANCE_MUTATION_CHANCE_MULTIPLIER_EFFECT_KEY = \"AppearanceMutationChanceMultiplier\""
        ));
        assertTrue(breedingContent.contains("withMutationChanceMultiplier(resolvePairMutationChanceMultiplier"));

        String inheritanceContent = readSource("npc", "progression", "CompanionAttachmentInheritanceService.java");
        assertTrue(inheritanceContent.contains("withMutationChanceMultiplier(double multiplier)"));
    }

    private static String readSource(String... parts) throws IOException {
        String[] pathParts = new String[5 + parts.length];
        pathParts[0] = "main";
        pathParts[1] = "java";
        pathParts[2] = "com";
        pathParts[3] = "alechilles";
        pathParts[4] = "alecstamework";
        System.arraycopy(parts, 0, pathParts, 5, parts.length);
        Path path = Path.of("src", Arrays.copyOf(pathParts, pathParts.length));
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

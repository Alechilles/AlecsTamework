package com.alechilles.alecstamework.architecture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the strict persisted-lifecycle signal from birth plan through component initialization. */
class BreedingPersistedLifecycleWiringTest {
    private static final Path ACTIONS = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "npc", "actions"
    );

    @Test
    void preparedChildCarriesPlannedOnlyResolutionThroughPostSpawnProgression() throws Exception {
        String prepared = read(ACTIONS.resolve("BreedingPreparedChildSpawnService.java"));
        String postSpawn = read(ACTIONS.resolve("BreedingOffspringPostSpawnService.java"));
        String progression = read(ACTIONS.resolve("BreedingOffspringProgressionService.java"));
        String lifeStage = read(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "npc", "progression", "CompanionLifeStageService.java"
        ));

        assertTrue(prepared.contains(
                "LifecycleFamilyResolution.PLANNED_SELECTION_ONLY"
        ));
        assertTrue(postSpawn.contains("request.lifecycleResolution()"));
        assertTrue(progression.contains("lifecycleResolution"));
        assertTrue(lifeStage.contains(
                "familyResolution == LifecycleFamilyResolution.PLANNED_SELECTION_ONLY"
        ));
        assertTrue(lifeStage.contains("component.setAdultRoleId(adultRoleId)"));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

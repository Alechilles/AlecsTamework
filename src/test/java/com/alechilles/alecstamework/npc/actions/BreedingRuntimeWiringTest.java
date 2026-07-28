package com.alechilles.alecstamework.npc.actions;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static regressions for the live-only breeding architecture. */
class BreedingRuntimeWiringTest {
    @Test
    void manualAndPassiveEntrypointsUseTheSameLiveFacade() throws Exception {
        String manual = source("InteractionBreedingEffects.java");
        String passive = source("PassiveBreedingSweepService.java");

        assertTrue(manual.contains("new BreedingOffspringService"));
        assertTrue(passive.contains("new BreedingOffspringService"));
        assertFalse(manual.contains("TameworkBreedingServices"));
        assertFalse(passive.contains("PopulationSweepContext"));
    }

    @Test
    void pairingPathHasNoDurableAdmissionOrReplayDependencies() throws Exception {
        String facade = source("BreedingOffspringService.java");
        String pairing = source("BreedingHytalePairingService.java");
        String birth = source("BreedingOffspringBirthService.java");
        String combined = facade + pairing + birth;

        assertFalse(combined.contains("npc.breeding"));
        assertFalse(combined.contains("PreparedBreeding"));
        assertFalse(combined.contains("PopulationAdmission"));
        assertFalse(combined.contains("BreedingBirthJob"));
        assertFalse(combined.contains("Replay"));
        assertFalse(combined.contains("Persistence"));
    }

    @Test
    void delayedWorkUsesStableIdsAndLiveCapAuthority() throws Exception {
        String delayed = source("BreedingPairingEffectsService.java");
        String pairing = source("BreedingHytalePairingService.java");
        String limits = source("BreedingClaimLimitPolicyService.java");

        assertTrue(delayed.contains("UUID parentAUuid"));
        assertTrue(delayed.contains("pairing.world().execute"));
        assertFalse(delayed.contains("Ref<EntityStore> parentARef"));
        assertTrue(pairing.contains("() -> birthService.spawn(world, context)"));
        assertFalse(pairing.contains("() -> birthService.spawn(candidate.world(), context)"));
        assertTrue(limits.contains("OwnerPopulationCapService.evaluateAcquisition"));
    }

    @Test
    void julyBreedingJobPackageIsGone() throws Exception {
        Path directory = Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/breeding"
        );
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".java")));
        }
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/actions",
                fileName
        )).replace("\r\n", "\n");
    }
}

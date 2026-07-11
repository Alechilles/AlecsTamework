package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards sweep-scoped policy, provider-lookup, and committed-occupancy reuse. */
class PassiveBreedingPopulationContextArchitectureTest {
    private static final Path MAIN = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework"
    );

    @Test
    void passiveSweepCreatesOneLazyContextAndPassesItToEveryCandidate() throws IOException {
        String sweep = read("npc/actions/PassiveBreedingSweepService.java");
        String lazy = read("npc/actions/BreedingPopulationSweepContext.java");
        String offspring = read("npc/actions/BreedingOffspringService.java");

        assertTrue(sweep.contains(
                "BreedingPopulationSweepContext populationContext = new BreedingPopulationSweepContext()"
        ));
        assertTrue(sweep.contains("commandBuffer,\n                    populationContext"));
        assertTrue(lazy.contains("if (context == null)"));
        assertTrue(lazy.contains("context = service.openPreparationContext()"));
        assertTrue(offspring.contains("populationContext.resolve(populationService)"));
        assertTrue(offspring.contains("prepareAsync(admissionRequest, preparationContext)"));
    }

    @Test
    void contextOwnsOneLookupSessionAndOneOptionalOccupancySnapshot() throws IOException {
        String service = read("ownership/BreedingPopulationAdmissionService.java");
        String batch = read("ownership/CompanionPopulationBatchAdmissionCoordinator.java");
        String snapshots = read("integration/claims/ClaimPopulationSnapshotService.java");

        assertTrue(service.contains("public PreparationContext openPreparationContext()"));
        assertTrue(service.contains("ClaimLookupSession lookupSession = new ClaimLookupSession("));
        assertTrue(service.contains("? claimOccupancyIndex.snapshot() : null"));
        assertTrue(service.contains("context.lookupSession"));
        assertTrue(service.contains("context.occupancySnapshot"));
        assertTrue(batch.contains("@Nullable ClaimOccupancySnapshot sharedSnapshot"));
        assertTrue(snapshots.contains("snapshot(index.snapshot(), target, lookupSession)"));
    }

    @Test
    void nearbyPopulationUsesTheEngineSpatialIndexInsteadOfAFullStoreScan() throws IOException {
        String populationTypes = read("npc/actions/BreedingPopulationTypeService.java");

        assertTrue(populationTypes.contains("getEntitySpatialResourceType()"));
        assertTrue(populationTypes.contains("getSpatialStructure().collect("));
        assertTrue(!populationTypes.contains("forEachChunk("));
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(MAIN.resolve(relativePath)).replace("\r\n", "\n");
    }
}

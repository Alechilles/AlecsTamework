package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects the structural extractions made during claims and owner-population hardening. */
class ClaimsOwnerPopulationStructuralPolicyTest {
    private static final Path JAVA_ROOT = Path.of("src/main/java/com/alechilles/alecstamework");

    @Test
    void persistenceWriteQueueRemainsAFocusedOrchestrator() throws IOException {
        assertLineLimit("persistence/sqlite/PersistenceWriteQueue.java", 500);
        assertLineLimit("persistence/sqlite/PersistenceWriteQueueMetrics.java", 500);
        assertLineLimit("persistence/sqlite/PersistenceWriteTask.java", 500);
        assertLineLimit("persistence/sqlite/SqliteBusyFailureClassifier.java", 500);
    }

    @Test
    void populationRepositoryDelegatesJournalSqlToFocusedStore() throws IOException {
        assertLineLimit("persistence/sqlite/CompanionPopulationRepository.java", 500);
        assertLineLimit("persistence/sqlite/CompanionPopulationJournalStore.java", 500);
    }

    @Test
    void settingsPageStaysBelowExistingClassHardCeiling() throws IOException {
        assertLineLimit("ui/TameworkSettingsPage.java", 800);
        assertLineLimit("ui/TameworkSettingsFormParser.java", 500);
    }

    @Test
    void interactionAndBreedingOrchestratorsDoNotRegrowPastTheirBaselines() throws IOException {
        assertLineLimit("npc/actions/ActionTameworkInteract.java", 571);
        assertLineLimit("npc/actions/TameworkInteractEffects.java", 622);
        assertLineLimit("npc/actions/BreedingOffspringProgressionService.java", 499);
        assertLineLimit("npc/actions/InteractionLegacyAdoptionService.java", 500);
        assertLineLimit("npc/actions/InteractionOwnerContinuationEffects.java", 500);
        assertLineLimit("npc/actions/InteractionHarvestEffects.java", 500);
        assertLineLimit("npc/actions/BreedingPlannedOwnerResolver.java", 500);
    }

    @Test
    void touchedLegacyDeathOrchestratorMustRemainSmallerThanItsBaseline() throws IOException {
        assertLineLimit("items/CommandLinkedNpcDeathService.java", 1390);
        assertLineLimit("items/CommandLinkedNpcDeathProfileWriter.java", 500);
    }

    @Test
    void legacyTameClaimFacadeCannotReintroduceAFullEcsPopulationScan() throws IOException {
        String source = Files.readString(
                JAVA_ROOT.resolve("npc/actions/LegacyClaimPopulationLookupService.java")
        );

        assertFalse(source.contains("forEachChunk("));
        assertTrue(source.contains("occupancyIndex.snapshot()"));
        assertTrue(source.contains("ClaimPopulationSnapshotService"));
    }

    @Test
    void settingsAndShutdownOwnBothOptionalClaimCapabilityCaches() throws IOException {
        String plugin = Files.readString(JAVA_ROOT.resolve("Tamework.java"));
        String settings = Files.readString(JAVA_ROOT.resolve("ui/TameworkSettingsPage.java"));
        String api = Files.readString(JAVA_ROOT.resolve("api/internal/TameworkApiImpl.java"));

        assertTrue(plugin.contains("new OwnerDamageFilterSystem(getLogger(), damagePolicy)"));
        assertTrue(plugin.contains("implementation.close()"));
        assertTrue(settings.contains("populationRuntime.claimProviderRegistry().onSettingsChanged()"));
        assertTrue(settings.contains("implementation.onRuntimeSettingsChanged()"));
        assertTrue(api.contains("damagePolicy.onRuntimeSettingsChanged()"));
        assertTrue(api.contains("damagePolicy.close()"));
    }

    @Test
    void runtimeAndPublicApiShareLiveDamageOwnerPrecedence() throws IOException {
        String runtime = Files.readString(JAVA_ROOT.resolve("damage/OwnerDamageFilterSystem.java"));
        String api = Files.readString(JAVA_ROOT.resolve("api/internal/TameworkApiImpl.java"));
        String resolver = Files.readString(JAVA_ROOT.resolve(
                "damage/TamedDamageOwnerPolicyResolver.java"
        ));

        assertTrue(runtime.contains("TamedDamageOwnerPolicyResolver.resolve("));
        assertTrue(api.contains("TamedDamageOwnerPolicyResolver.resolveLive("));
        int ownerComponent = resolver.indexOf("owner.getOwnerId()");
        int commandLinks = resolver.indexOf("links.getOwnerId()");
        int npcName = resolver.indexOf("npcName.getOwnerId()");
        assertTrue(ownerComponent >= 0 && commandLinks > ownerComponent);
        assertTrue(npcName > commandLinks);
    }

    @Test
    void directOwnerComponentRemovalCannotBecomeAnUnjournaledRelease() throws IOException {
        String source = Files.readString(JAVA_ROOT.resolve(
                "ownership/reconciliation/CompanionOwnerComponentReconciliationSystem.java"
        ));

        assertTrue(source.contains("observeRemoval(ref, component, store, \"owner-component-removed\")"));
        assertTrue(source.contains("outcome != CompanionPopulationRuntimeReconciler.ObservationOutcome."
                + "SUPPRESSED_IN_FLIGHT"));
        assertTrue(source.contains("outcome != CompanionPopulationRuntimeReconciler.ObservationOutcome."
                + "AUTHORIZED_RELEASE"));
        assertTrue(source.contains("mutationService.restoreUnauthorizedRemovalBuffered("));
        assertFalse(source.contains("commandBuffer.putComponent(ref, ownerType, component.clone())"));
        assertFalse(source.contains("observe(ref, null, store, \"owner-component-removed\")"));
    }

    private static void assertLineLimit(String relativePath, int limit) throws IOException {
        Path path = JAVA_ROOT.resolve(relativePath);
        int lines = Files.readAllLines(path).size();
        assertTrue(lines <= limit, () -> relativePath + " has " + lines + " lines; limit is " + limit);
    }
}

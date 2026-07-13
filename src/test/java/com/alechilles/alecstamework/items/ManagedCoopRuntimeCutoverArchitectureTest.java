package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static guardrails for the decomposed managed-coop cutover boundary. */
class ManagedCoopRuntimeCutoverArchitectureTest {
    private static final Path MAIN = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");
    private static final List<String> FILES = List.of(
            "ManagedCoopAuthorityEligibilityIndex.java",
            "ManagedCoopCrossWorldAliasRetirement.java",
            "ManagedCoopCrossWorldAliasRetirementCoordinator.java",
            "HytaleManagedCoopCrossWorldAliasRuntimeGateway.java",
            "ManagedCoopChunkScanner.java",
            "ManagedCoopCaptureCandidate.java",
            "ManagedCoopRuntimeCandidateScanner.java",
            "ManagedCoopProjectionMarkerPolicy.java",
            "ManagedCoopLifecycleAdmissionGuard.java",
            "ManagedCoopReleasePopulationInputFactory.java",
            "ManagedCoopReleasePopulationCoordinator.java",
            "ManagedCoopRuntimeSweepPlanner.java",
            "ManagedCoopRuntimeOperationDispatcher.java",
            "HytaleManagedCoopReleaseProjectionGateway.java",
            "ManagedCoopRuntimeSweepOrchestrator.java",
            "ManagedCoopRuntimeComposition.java",
            "ManagedCoopRuntimeSystem.java");

    @Test
    void cutoverClassesStayFocusedAndNeverReintroduceLegacyOrVanillaMutation() throws Exception {
        for (String file : FILES) {
            Path path = MAIN.resolve(file);
            String source = Files.readString(path);
            assertTrue(Files.readAllLines(path).size() <= 500, file + " exceeds 500 lines");
            assertFalse(source.contains("CommandLinkedNpcCoopService"), file);
            assertFalse(source.contains("captureSnapshotForLedger"), file);
            assertFalse(source.contains("getCoopSnapshot"), file);
            assertFalse(source.contains("tryPutResident"), file);
            assertFalse(source.contains("spawnEntity("), file);
            assertFalse(source.contains("Class.forName("), file);
            assertFalse(source.contains("TameworkReflectionAccessCache"), file);
            assertFalse(source.contains("java.lang.reflect"), file);
        }
    }

    @Test
    void asyncDispatcherDoesNotCaptureLiveArgumentsInContinuations() throws Exception {
        String source = Files.readString(MAIN.resolve("ManagedCoopRuntimeOperationDispatcher.java"));

        assertTrue(source.contains("captures.capture("));
        assertTrue(source.contains("capture.thenCompose(this::afterCapture)"));
        assertTrue(source.contains("releases.coordinate(new ReleaseAttempt"));
        assertTrue(source.contains("projections.project(new ReleaseProjectionCommand"));
        assertFalse(source.contains("thenCompose(outcome -> operations.capture"));
        assertFalse(source.contains("thenCompose(outcome -> releases.release"));
    }

    @Test
    void normalLifecycleDispatchRunsBeforeRestartRecoveryToPreventGlobalLeaseStarvation()
            throws Exception {
        String source = Files.readString(MAIN.resolve(
                "ManagedCoopRuntimeSweepOrchestrator.java"));
        String importing = "ImportFilter imported = filterImports";
        String planning = "boolean captureDemand = planner.needsCaptureCandidates";
        String dispatching = "for (CoopPlan coop : plan.coops())";
        String recovery = "boolean recoveryAttempted = startLifecycleRecovery";

        assertTrue(source.contains("interface ImportBehavior"));
        assertTrue(source.contains("interface LifecycleRecoveryBehavior"));
        assertTrue(source.contains("lifecycleRecovery.recover(worldName, contexts)"));
        assertTrue(source.indexOf(importing) >= 0);
        assertTrue(source.indexOf(planning) >= 0);
        assertTrue(source.indexOf(dispatching) >= 0);
        assertTrue(source.indexOf(recovery) >= 0);
        assertTrue(source.indexOf(importing) < source.indexOf(planning));
        assertTrue(source.indexOf(planning) < source.indexOf(dispatching));
        assertTrue(source.indexOf(dispatching) < source.indexOf(recovery));
    }

    @Test
    void pluginRegistersOnlyTheV5CompositionAndOwnsStaticIntakeLifecycle() throws Exception {
        String plugin = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"));
        String composition = Files.readString(MAIN.resolve(
                "ManagedCoopRuntimeComposition.java"));

        assertTrue(plugin.contains("new ManagedCoopRuntimeComposition("));
        assertTrue(plugin.contains("managedCoopRuntime.runtimeSystem()"));
        assertTrue(plugin.contains("managedCoopRuntime.staleEntitySuppressionSystem()"));
        assertTrue(plugin.contains("managedCoopRuntime.sourceRetirementSystem()"));
        assertTrue(plugin.contains("managedCoopRuntime.close()"));
        assertFalse(plugin.contains("CommandCoopManagedWildCaptureSystem"));
        assertFalse(Files.exists(MAIN.resolve("CommandCoopManagedWildCaptureSystem.java")));
        assertTrue(composition.contains("ManagedCoopItemIntakeRuntime.install("));
        assertTrue(composition.contains("ManagedCoopItemIntakeRuntime.clear(itemIntakeHandler)"));
        assertTrue(composition.contains("ManagedCoopVanillaImportBehavior"));
        assertTrue(composition.contains("ManagedCoopRemovedCoopReconciler"));
        assertTrue(composition.contains("new ManagedCoopStaleEntitySuppressionSystem("));
        assertTrue(composition.contains(
                "ManagedCoopAuthorityEligibilityIndex authorityEligibility"));
        assertTrue(composition.contains(
                "new ManagedCoopChunkScanner(authorityEligibility)"));
        assertTrue(composition.contains("staleEntitySuppressionSystem::reevaluate"));
        assertTrue(composition.contains("authorityEligibility.invalidateAll()"));
        assertTrue(composition.contains("authorityEligibility.close()"));
        assertTrue(composition.contains("ManagedCoopImportControl.shared()"));
        assertTrue(composition.contains("importControl.clearAll()"));
        assertTrue(composition.contains("persistence.getNpcIdentityRepository()"));
        assertTrue(composition.contains("loadedIdentities"));
    }

    @Test
    void currentAuthorityPublicationRetriesLoadedNpcsAndRevokesOnLifecycleChanges()
            throws Exception {
        String orchestrator = Files.readString(MAIN.resolve(
                "ManagedCoopRuntimeSweepOrchestrator.java"));
        String plugin = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"));
        int contextScan = orchestrator.indexOf("contextScanner.scan(chunkStore, world)");
        int reevaluation = orchestrator.indexOf("reevaluateStaleEntities(entityStore)");
        int imports = orchestrator.indexOf("ImportFilter imported = filterImports");

        assertTrue(contextScan >= 0 && contextScan < reevaluation);
        assertTrue(reevaluation < imports);
        assertTrue(plugin.contains("onWorldRemovedForProgressionTiming"));
        assertTrue(plugin.contains("invalidateManagedAuthorityWorld(worldName)"));
        assertTrue(plugin.contains("managedCoopRuntime.invalidateManagedAuthorityEvidence()"));
    }

    @Test
    void relocationLifecycleNoLongerObservesVanillaCoopResidents() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/"
                        + "CommandNpcRelocationOnLoadSystem.java"));

        assertFalse(source.contains("CoopResidentComponent"));
        assertFalse(source.contains("CoopBlock"));
        assertFalse(source.contains("CommandLinkedNpcCoopService"));
        assertFalse(source.contains("CoopResidentStateSnapshotService"));
        assertFalse(source.contains("COOP_RESIDENTS_FIELD"));
    }
}

package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CaptureAttemptRuntimeWiringTest {
    @Test
    void gameplayUsesDurableCoordinatorAndCapabilityIsRecoveryGated() throws Exception {
        String plugin = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"));
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));
        String runtime = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "SpawnerCaptureAttemptRuntimeCoordinator.java"));
        String api = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/api/internal/TameworkApiImpl.java"));

        assertTrue(plugin.contains("captureAttemptCoordinator.recover(128).join()"));
        assertTrue(plugin.contains("captureAttemptRuntimeReady ? captureAttemptCoordinator : null"));
        assertTrue(plugin.contains("if (captureAttemptRuntimeReady && api instanceof TameworkApiImpl"));
        assertTrue(handler.contains("captureAttemptRuntime.prepareAndResolve("));
        assertTrue(handler.contains("captureAttemptRuntime.revalidateBeforeApply("));
        assertTrue(runtime.contains("attempts.resolve(request)"));
        assertTrue(runtime.contains("attempts.beginApply(effective.attemptId())"));
        assertTrue(runtime.contains("attempts.commit(attemptId)"));
        assertTrue(runtime.contains("channelAttempts.put(playerUuid, attempt)"));
        assertTrue(runtime.contains("CaptureAttemptHandle.forDispatch"));
        assertTrue(runtime.contains("attempt.sourceContextJson(world.getName())"));
        assertTrue(runtime.contains("attempt.sourceFingerprint().equals"));
        assertTrue(handler.contains("missing-channel-attempt-identity"));
        assertTrue(!runtime.contains("attemptId == null ? UUID.randomUUID() : attemptId"));
        assertTrue(!handler.contains("captureBurstParticleSystem, UUID.randomUUID()"));
        int populationPrepare = runtime.indexOf("finalizer.prepareCapture(");
        int roll = runtime.indexOf("attempts.resolve(request)");
        assertTrue(populationPrepare >= 0 && roll > populationPrepare);
        assertTrue(runtime.contains("mutation.populationOperationId()"));
        assertTrue(runtime.contains("attempts.revalidateBeforeApply("));
        assertTrue(api.contains("capabilities.add(TameworkApiCapability.CAPTURE_POLICY)"));
    }

    @Test
    void diagnoseCommandPublishesIntegrationReadiness() throws Exception {
        String root = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkCommandRoot.java"));
        String diagnose = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkDiagnoseCommand.java"));
        String diagnostics = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/"
                        + "TameworkIntegrationDiagnosticsService.java"));
        assertTrue(root.contains("new TameworkDiagnoseCommand()"));
        assertTrue(diagnose.contains("diagnostics.overview()"));
        assertTrue(diagnose.contains("case \"capture-attempt\""));
        assertTrue(diagnose.contains("diagnostics.captureAttempt(arguments.get(1))"));
        assertTrue(diagnostics.contains("Integration readiness:"));
        assertTrue(diagnostics.contains("capturePolicy="));
        assertTrue(diagnostics.contains("bondedVessels="));
        assertTrue(diagnostics.contains("populationGroups="));
        assertTrue(diagnostics.contains("provisioning="));
        assertTrue(diagnostics.contains("Capture attempts:"));
        assertTrue(diagnostics.contains("entropy=<redacted>"));

        String selfTest = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/selftest/ApiSelfTestRunner.java"));
        assertTrue(selfTest.contains("HYDRAGON_INTEGRATIONS"));
        assertTrue(selfTest.contains("profile data transactions capability ready"));
    }

    @Test
    void companionProvisioningIsJournalBackedAndRecoveryGated() throws Exception {
        String plugin = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"));
        String api = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/api/internal/TameworkApiImpl.java"));

        assertTrue(plugin.contains("new SqliteProvisioningOperationJournal("));
        assertTrue(plugin.contains("backend.recover().toCompletableFuture().join()"));
        assertTrue(plugin.contains("coordinator.recover().toCompletableFuture().join()"));
        assertTrue(plugin.contains("companionProvisioningBackend.recoveryReady()"));
        assertTrue(plugin.contains("new HytaleProvisionedCompanionProjectionPort("));
        assertTrue(plugin.contains("companionProvisioningBackend.activeProjectionReady()"));
        assertTrue(plugin.contains("apiEventBus::emitCompanionProvisioned"));
        assertTrue(plugin.contains("ownerPopulationRuntime.installPopulationGroups("));
        assertTrue(plugin.contains("apiEventBus::emitPopulationGroupEvent"));
        assertTrue(plugin.contains("ownerPopulationRuntime.reconcilePopulationGroups().join()"));
        assertTrue(plugin.contains("ownerPopulationRuntime.publishPopulationGroupLimitChanges("));
        assertTrue(plugin.contains("activatePopulationGroupsIfReady()"));
        assertTrue(plugin.contains(".whenComplete(this::onPopulationRecoveryFinished)"));
        assertTrue(plugin.contains("ProductionBondedVesselRuntime.compose("));
        assertTrue(plugin.contains("new BondedVesselUnifiedPopulationPort(ownerPopulationRuntime)"));
        assertTrue(plugin.contains("new LoadedNpcBondedVesselProjectionEvidencePort("));
        assertTrue(plugin.contains("new HytaleBondedVesselWorldProjectionPort("));
        assertTrue(plugin.contains("runtime.bootstrap().recoverAndActivate()"));
        assertTrue(plugin.contains("apiEventBus::emitBondedVesselEvent"));
        int reviveBootstrap = plugin.indexOf("reviveEligibility.bootstrap(");
        int reviveEvents = plugin.indexOf(
                "reviveEligibility.setEventSink(apiEventBus::emitCanonicalCompanionLifecycleEvent)");
        int deathSystems = plugin.indexOf("TameworkPopulationRuntimeLifecycle.registerSystems(");
        assertTrue(reviveBootstrap >= 0 && deathSystems > reviveBootstrap);
        assertTrue(reviveEvents > reviveBootstrap && deathSystems > reviveEvents);
        assertTrue(api.contains("activateCompanionProvisioningRuntime("));
        assertTrue(api.contains("capabilities.add(TameworkApiCapability.COMPANION_PROVISIONING)"));
        assertTrue(api.contains("boolean exactEvidenceAuthorityReady"));
        assertTrue(api.contains("boolean mutationAuthorityReady"));
        assertTrue(api.contains("boolean allPositivePathsInstalled"));
    }
}

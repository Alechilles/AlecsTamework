package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(api.contains(
                "capabilities.add(TameworkApiCapability.CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION)"));
    }

    @Test
    void terminalApplyDoesNotRequireAConsumedCaptureSourceToRemainInTheHotbar() throws Exception {
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));

        assertTrue(handler.contains(
                "if (!outcomeResolved && !captureAttemptRuntime.sourceMatches(player, attempt))"));
        assertFalse(handler.contains(
                "if (!captureAttemptRuntime.sourceMatches(player, attempt))"));
        assertTrue(handler.contains("captureAttemptRuntime.revalidateBeforeApply("));
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
        assertTrue(diagnostics.contains("populationGroups="));
        assertTrue(diagnostics.contains("Capture attempts:"));
        assertTrue(diagnostics.contains("entropy=<redacted>"));

        String selfTest = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/selftest/ApiSelfTestRunner.java"));
        assertTrue(selfTest.contains("HYDRAGON_INTEGRATIONS"));
        assertTrue(selfTest.contains("profile data transactions capability ready"));
    }

}

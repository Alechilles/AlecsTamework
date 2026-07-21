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
        String api = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/api/internal/TameworkApiImpl.java"));

        assertTrue(plugin.contains("captureAttemptCoordinator.recover(128).join()"));
        assertTrue(plugin.contains("captureAttemptRuntimeReady ? captureAttemptCoordinator : null"));
        assertTrue(plugin.contains("if (captureAttemptRuntimeReady && api instanceof TameworkApiImpl"));
        assertTrue(handler.contains("captureAttemptCoordinator.resolve(request)"));
        assertTrue(handler.contains("captureAttemptCoordinator.beginApply(attemptId)"));
        assertTrue(handler.contains("captureAttemptCoordinator.commit(finalizedAttemptId)"));
        assertTrue(handler.contains("channelAttemptIds.put(playerUuid.getUuid(), UUID.randomUUID())"));
        assertTrue(handler.contains("missing-channel-attempt-identity"));
        assertTrue(!handler.contains("attemptId == null ? UUID.randomUUID() : attemptId"));
        int populationPrepare = handler.indexOf("captureFinalizerService.prepareCapture(");
        int roll = handler.indexOf("captureAttemptCoordinator.resolve(request)");
        assertTrue(populationPrepare >= 0 && roll > populationPrepare);
        assertTrue(handler.contains("preparedMutation.populationOperationId()"));
        assertTrue(handler.contains("captureAttemptCoordinator.revalidateBeforeApply("));
        assertTrue(api.contains("capabilities.add(TameworkApiCapability.CAPTURE_POLICY)"));
    }

    @Test
    void diagnoseCommandPublishesIntegrationReadiness() throws Exception {
        String root = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkCommandRoot.java"));
        String diagnose = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkDiagnoseCommand.java"));
        assertTrue(root.contains("new TameworkDiagnoseCommand()"));
        assertTrue(diagnose.contains("Integration readiness:"));
        assertTrue(diagnose.contains("capturePolicy="));
        assertTrue(diagnose.contains("bondedVessels="));
        assertTrue(diagnose.contains("populationGroups="));
        assertTrue(diagnose.contains("provisioning="));

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
        assertTrue(api.contains("activateCompanionProvisioningRuntime("));
        assertTrue(api.contains("capabilities.add(TameworkApiCapability.COMPANION_PROVISIONING)"));
        assertTrue(api.contains("boolean exactEvidenceAuthorityReady"));
        assertTrue(api.contains("boolean mutationAuthorityReady"));
        assertTrue(api.contains("boolean allPositivePathsInstalled"));
    }
}

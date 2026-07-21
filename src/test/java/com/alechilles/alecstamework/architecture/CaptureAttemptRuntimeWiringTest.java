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
    }
}

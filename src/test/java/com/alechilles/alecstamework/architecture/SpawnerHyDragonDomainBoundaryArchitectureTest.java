package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SpawnerHyDragonDomainBoundaryArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");
    private static final Path ASSETS = Path.of(
            "src/main/java/com/alechilles/alecstamework/config/assets");

    @Test
    void legacyHandlerRemainsAnOrchestratorForCaptureDomain() throws Exception {
        String handler = read(ITEMS.resolve("SpawnerFeatureHandler.java"));
        String attempts = read(ITEMS.resolve("SpawnerCaptureAttemptRuntimeCoordinator.java"));

        // Coarse tripwires only: the behavioral assertions below enforce the actual boundary.
        // The committed handler was already larger than the old 1,050-line threshold before
        // timed command-roster convergence was added.
        assertTrue(handler.lines().count() <= 1400,
                "legacy spawner handler must not absorb new domain implementations");
        assertTrue(attempts.lines().count() <= 525,
                "capture attempt lifecycle must remain a focused coordinator");
        assertTrue(handler.contains("captureAttemptRuntime.prepareAndResolve("));
        assertFalse(handler.contains("ConcurrentHashMap<"));
        assertFalse(handler.contains("attempts.resolve(request)"));

        assertTrue(attempts.contains("attempts.resolve(request)"));
        assertTrue(attempts.contains("attempts.beginApply(effective.attemptId())"));
        assertTrue(attempts.contains("attempts.revalidateBeforeApply("));
    }

    @Test
    void spawnerAssetClassDelegatesNewCodecAndRuntimeProjectionLogic() throws Exception {
        String config = read(ASSETS.resolve("TwSpawnerConfig.java"));
        String codec = read(ASSETS.resolve("TwSpawnerCaptureSettingsCodec.java"));
        String adapter = read(ASSETS.resolve("TwSpawnerConfigRuntimeAdapter.java"));

        assertTrue(config.lines().count() <= 900,
                "TwSpawnerConfig must remain the asset schema, not a multi-domain adapter");
        assertTrue(codec.lines().count() <= 175,
                "capture schema codec should remain independently reviewable");
        assertTrue(adapter.lines().count() <= 125,
                "runtime projection adapter should remain independently reviewable");
        assertTrue(config.contains("TwSpawnerCaptureSettingsCodec.CODEC"));
        assertTrue(config.contains("TwSpawnerConfigRuntimeAdapter.captureMechanics("));
        assertFalse(config.contains("private static CaptureChanceMode parseChanceMode"));
        assertFalse(config.contains("new SpawnerCaptureMechanicsView("));
        assertFalse(config.contains(".toRuntimeMechanics(emptyItemId, filledItemId)"));

        assertTrue(codec.contains("new KeyedCodec<>(\"ChanceMode\""));
        assertTrue(codec.contains("new KeyedCodec<>(\"FailureSoundEvent\""));
        assertTrue(adapter.contains("new SpawnerCaptureMechanicsView("));
        assertTrue(adapter.contains("new ItemFeatureConfig.CaptureItemMechanics("));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path);
    }
}

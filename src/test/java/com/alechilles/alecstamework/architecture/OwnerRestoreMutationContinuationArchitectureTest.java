package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards restore, spawner, and release continuations against pre-admission side effects. */
class OwnerRestoreMutationContinuationArchitectureTest {
    private static final Path MAIN = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework"
    );

    @Test
    void replacementSpawnPathsUsePreparedPopulationAdmission() throws IOException {
        List<Path> paths = List.of(
                item("NpcSpawnCommandService.java"),
                item("CommandLostRecoveryCoordinator.java"),
                item("CommandRespawnService.java")
        );
        for (Path path : paths) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertFalse(
                    source.matches("(?s).*putComponent\\s*\\([^;]*new\\s+TameworkOwnerComponent\\s*\\(.*"),
                    path + " must not write owner components directly"
            );
        }
        assertTrue(readItem("NpcSpawnCommandService.java").contains("batchSpawnService.schedule("));
        String recovery = readItem("CommandLostRecoveryCoordinator.java");
        assertTrue(recovery.contains("operationRepository.claim("));
        assertTrue(recovery.contains("projectionSpawner.spawn("));
        assertTrue(recovery.contains("operationRepository.finalizeRecovery("));
        assertTrue(readItem("CommandRespawnService.java").contains("preparedSpawnService.schedule("));
        String prepared = readItem("CompanionPreparedSpawnService.java");
        assertTrue(prepared.contains("admissionService.writeSpawnHolder"));
        assertTrue(prepared.indexOf("admissionService.claimForSpawn") < prepared.indexOf("npcPlugin.spawnEntity"));
        String handler = readItem("CommandItemFeatureHandler.java");
        assertTrue(handler.contains("ownerReleaseService.release("));
        assertTrue(handler.contains("respawnService.respawnDeadLinkedNpc("));
        assertTrue(handler.contains("lostRecoveryCoordinator.request("));
    }

    @Test
    void replacementRestoreSideEffectsRunAfterCanonicalAdmission() throws IOException {
        String respawn = readItem("CommandRespawnService.java");
        int respawnPrepare = respawn.indexOf("preparedSpawnService.schedule(");
        int respawnLive = respawn.indexOf("public void onSpawned", respawnPrepare);
        int respawnFinalize = respawn.indexOf("public boolean finalizeSource", respawnLive);
        int clearDeathSnapshot = respawn.indexOf("deathService.clearDeadSnapshot", respawnFinalize);
        assertTrue(respawnPrepare >= 0 && respawnLive > respawnPrepare);
        assertTrue(clearDeathSnapshot > respawnFinalize, "death snapshot must survive admission/commit denial");

        String lost = readItem("CommandLostRecoveryCoordinator.java");
        int lostClaim = lost.indexOf("operationRepository.claim(");
        int lostSpawn = lost.indexOf("projectionSpawner.spawn(", lostClaim);
        int recordProjection = lost.indexOf("operationRepository.recordProjectionCreated(", lostSpawn);
        int finalizeRecovery = lost.indexOf("operationRepository.finalizeRecovery(", recordProjection);
        assertTrue(lostClaim >= 0 && lostSpawn > lostClaim);
        assertTrue(recordProjection > lostSpawn && finalizeRecovery > recordProjection,
                "lost recovery must journal the exact projection before finalizing its source");
    }

    @Test
    void captureAndReleaseDespawnOnlyFromAppliedContinuations() throws IOException {
        String capture = readItem("SpawnerCaptureFinalizerService.java");
        int captureSchedule = capture.indexOf("scheduler.scheduleWithDurableContext(");
        int captureApplied = capture.indexOf("public void onApplied", captureSchedule);
        int captureDespawn = capture.indexOf(
                "despawnNpc(player, context.npcRef(), liveNpc)", captureApplied
        );
        assertTrue(captureSchedule >= 0 && captureApplied > captureSchedule);
        assertTrue(captureDespawn > captureApplied, "captured NPC must remain live when admission is denied");

        String command = readItem("CommandOwnerReleaseService.java");
        int releaseKey = command.indexOf("\"command-release:\"");
        int releaseApplied = command.indexOf("public void onApplied", releaseKey);
        int releaseDespawn = command.indexOf("liveNpc.setToDespawn()", releaseApplied);
        assertTrue(releaseKey >= 0 && releaseApplied > releaseKey);
        assertTrue(releaseDespawn > releaseApplied, "released NPC must remain intact when admission is denied");
    }

    private static Path item(String fileName) {
        return MAIN.resolve(Paths.get("items", fileName));
    }

    private static String readItem(String fileName) throws IOException {
        return Files.readString(item(fileName), StandardCharsets.UTF_8);
    }
}

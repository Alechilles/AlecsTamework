package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the intentionally small, single-authority spawner architecture. */
class SpawnerWildCaptureArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items"
    );

    @Test
    void channelCompletionEndsProcessLocalSessionBeforeTerminalCapture()
            throws Exception {
        String source = read("SpawnerFeatureHandler.java");
        int complete = source.indexOf("boolean completeCaptureChannel(");
        int take = source.indexOf("channels.take(player)", complete);
        int end = source.indexOf(
                "endCaptureChannel(player, targetRef, source)", take
        );
        int capture = source.indexOf(
                "captureFromItemInteraction(", end
        );

        assertTrue(complete >= 0);
        assertTrue(take > complete);
        assertTrue(end > take);
        assertTrue(capture > end);
    }

    @Test
    void handlerCarriesNoDurableAttemptOrRecoverySubsystem() throws Exception {
        String source = read("SpawnerFeatureHandler.java");

        assertTrue(source.contains("SpawnerCaptureRollService"));
        assertTrue(source.contains("captureAuthor.capture(intent)"));
        assertFalse(source.contains("CaptureAttemptCoordinator"));
        assertFalse(source.contains("CaptureAttemptJournal"));
        assertFalse(source.contains("CaptureAttemptRepository"));
        assertFalse(source.contains("sourceSpend"));
        assertFalse(source.contains("onAddPlayerToWorld"));
    }

    @Test
    void failedRollIsFeedbackOnlyAndNeverSubmitted() throws Exception {
        String source = read("SpawnerFeatureHandler.java");
        int evaluate = source.indexOf("captureRolls.evaluate(");
        int failed = source.indexOf(
                "SpawnerCaptureChanceService.Outcome.FAILED_ROLL", evaluate
        );
        int feedback = source.indexOf(
                "effects.playCaptureFailureEffects(", failed
        );
        int rejected = source.indexOf("return false;", feedback);
        int author = source.indexOf("captureAuthor.capture(intent)", rejected);

        assertTrue(evaluate >= 0);
        assertTrue(failed > evaluate);
        assertTrue(feedback > failed);
        assertTrue(rejected > feedback);
        assertTrue(author > rejected);
    }

    @Test
    void releaseIsOneCanonicalAuthorCall() throws Exception {
        String source = read("SpawnerFeatureHandler.java");

        assertTrue(source.contains("releaseAuthor.release("));
        assertFalse(source.contains("PreparedRestoreSpawn"));
        assertFalse(source.contains("captureRepository"));
        assertFalse(source.contains("lostService"));
    }

    @Test
    void newOrchestratorsStayBelowProjectHardCeiling() throws Exception {
        assertTrue(Files.readAllLines(
                ITEMS.resolve("SpawnerFeatureHandler.java")
        ).size() < 800);
        assertTrue(Files.readAllLines(
                ITEMS.resolve(
                        "persistence/SpawnerCaptureAuthor.java"
                )
        ).size() <= 500);
        assertTrue(Files.readAllLines(
                ITEMS.resolve(
                        "persistence/SpawnerCaptureProfileCoordinator.java"
                )
        ).size() <= 300);
        assertTrue(Files.readAllLines(
                ITEMS.resolve(
                        "persistence/SpawnerCaptureProfileGate.java"
                )
        ).size() <= 200);
        assertTrue(Files.readAllLines(
                ITEMS.resolve(
                        "persistence/SpawnerCapturedArtifactReleaseAuthor.java"
                )
        ).size() <= 500);
    }

    @Test
    void captureChannelRetainsAuthoredVfxFields() throws Exception {
        String interaction = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/"
                        + "TameworkCaptureChannelInteraction.java"
        ));

        assertTrue(interaction.contains(
                "\"CaptureBurstParticleSystem\", Codec.STRING"
        ));
        assertTrue(interaction.contains(
                "\"BeamNativeDurationSeconds\", Codec.DOUBLE"
        ));
        assertTrue(interaction.contains(
                "\"BeamFromTarget\", Codec.BOOLEAN"
        ));
        assertTrue(interaction.contains(
                "\"HomingProjectileEnabled\", Codec.BOOLEAN"
        ));
    }

    private String read(String file) throws Exception {
        return Files.readString(ITEMS.resolve(file));
    }
}

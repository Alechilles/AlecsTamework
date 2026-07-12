package com.alechilles.alecstamework.architecture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards crash/exception terminality for delayed breeding population batches. */
class BreedingAdmissionTerminalityArchitectureTest {
    private static final Path ACTIONS = Path.of(
            "src", "main", "java", "com", "alechilles", "alecstamework", "npc", "actions"
    );

    @Test
    void thrownSpawnActionStillRunsCancellationContinuation() throws Exception {
        String effects = Files.readString(
                ACTIONS.resolve("BreedingPairingEffectsService.java"), StandardCharsets.UTF_8
        );

        assertTrue(effects.contains("PairingState.SPAWNING"));
        assertTrue(effects.contains("pairing.canceledAction().run()"));
        assertTrue(effects.contains("catch (RuntimeException | LinkageError failure)"));
    }

    @Test
    void litterFinallyCancelsEveryUnitWithoutLiveTerminalOwnership() throws Exception {
        String service = Files.readString(
                ACTIONS.resolve("BreedingOffspringService.java"), StandardCharsets.UTF_8
        );

        assertTrue(service.contains("boolean[] terminalUnits"));
        assertTrue(service.contains("if (!terminalUnits[index])"));
        assertTrue(service.contains("cancelBreedingUnit(populationService, context, index"));
        assertTrue(service.contains("pairAdmissionRegistry.complete(context.pairToken())"));
    }

    /** Regression: a post-add exception must be recoverable by deterministic child UUID. */
    @Test
    void preparedSpawnReportsAmbiguityInsteadOfPresentingItAsSafeFailure() throws Exception {
        String spawn = Files.readString(
                ACTIONS.resolve("BreedingOffspringSpawnService.java"), StandardCharsets.UTF_8
        );
        String types = Files.readString(
                ACTIONS.resolve("BreedingSpawnTypes.java"), StandardCharsets.UTF_8
        );

        assertTrue(spawn.contains("PlannedCompanionSpawnProbe.probe("));
        assertTrue(spawn.contains("BreedingPreparedSpawnResult.ambiguous("));
        assertTrue(types.contains("boolean outcomeAmbiguous"));
    }

    @Test
    void preparedHandoffUsesOneTerminalOwnerAcrossWorldAndEffectFailures() throws Exception {
        String service = Files.readString(
                ACTIONS.resolve("BreedingOffspringService.java"), StandardCharsets.UTF_8
        );
        String terminality = Files.readString(
                ACTIONS.resolve("BreedingPreparedHandoffTerminality.java"), StandardCharsets.UTF_8
        );
        String handoff = Files.readString(
                ACTIONS.resolve("BreedingPreparedPairingHandoffService.java"), StandardCharsets.UTF_8
        );

        assertTrue(handoff.contains("terminality.cancel(\"breeding-world-finalization-failed\")"));
        assertTrue(service.contains("context.terminality().cancel(\"breeding-pairing-effects-canceled\")"));
        assertTrue(service.contains("context.terminality().transferToSpawn()"));
        assertTrue(terminality.contains("state.compareAndSet(State.OPEN, State.CANCELED)"));
        assertTrue(terminality.contains("runSafely(() -> populationCancellation.cancel(reason)"));
        assertTrue(terminality.contains("runSafely(nearbyRelease"));
        assertTrue(terminality.contains("runSafely(pairClose"));
    }

    @Test
    void ambiguousSpawnRetainsApplyingJournalForRecovery() throws Exception {
        String service = Files.readString(
                ACTIONS.resolve("BreedingOffspringService.java"), StandardCharsets.UTF_8
        );

        assertTrue(service.contains("if (spawn.outcomeAmbiguous())"));
        assertTrue(service.contains("markReadinessDegraded(\"breeding_spawn_outcome_ambiguous\")"));
        assertTrue(service.contains("APPLYING journal retained for startup recovery"));
    }
}

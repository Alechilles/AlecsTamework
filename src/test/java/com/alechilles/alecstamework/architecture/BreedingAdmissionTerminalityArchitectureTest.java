package com.alechilles.alecstamework.architecture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String execution = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "npc", "breeding", "BreedingJobExecutionService.java"
        ), StandardCharsets.UTF_8);
        String prepared = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "npc", "breeding", "BreedingPreparedPopulationRegistry.java"
        ), StandardCharsets.UTF_8);

        assertTrue(execution.contains("cancelPopulationSafely(job"));
        assertTrue(execution.contains("releaseChildReservation("));
        assertTrue(prepared.contains("public void cancelRemaining("));
        assertTrue(prepared.contains("definitelyCancelable(states[index])"));
        assertTrue(prepared.contains("state == UnitState.RESERVED || state == UnitState.APPLYING"));
        assertTrue(prepared.contains("states[index] = UnitState.MATERIALIZED"));
        assertTrue(prepared.contains("UnitState.COMMITTED"));
        assertTrue(prepared.contains("UnitState.CANCELED"));
    }

    @Test
    void deterministicAttemptAndChildIdentityAreInstalledBeforeWorldInsertion() throws Exception {
        String attemptSelector = Files.readString(
                ACTIONS.resolve("BreedingPairingAttemptSelector.java"), StandardCharsets.UTF_8
        );
        String spawn = Files.readString(
                ACTIONS.resolve("BreedingOffspringSpawnService.java"), StandardCharsets.UTF_8
        );
        String childSpawn = Files.readString(
                ACTIONS.resolve("BreedingPreparedChildSpawnService.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(attemptSelector.contains("BreedingAttemptIdentity.forPersistedCooldowns("));
        assertTrue(attemptSelector.contains("BreedingAttemptIdentity.forAppliedCooldowns("));
        assertTrue(attemptSelector.contains("this(UUID::randomUUID)"));
        assertTrue(spawn.contains("spawnPreparedWithFallback("));
        assertTrue(childSpawn.contains("preparedPopulation.claimForSpawn("));
        assertTrue(childSpawn.contains("preparedPopulation.writeSpawnHolder("));
        assertTrue(childSpawn.contains("preparedPopulation.markMaterialized("));
        assertTrue(childSpawn.contains("reservedOwner(reserved)"));
        assertTrue(childSpawn.contains("plannedLifecycleFamily(config, child)"));
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
        String handoff = Files.readString(
                ACTIONS.resolve("BreedingPreparedPairingHandoffService.java"), StandardCharsets.UTF_8
        );
        String prepared = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "npc", "breeding", "BreedingPreparedPopulationRegistry.java"
        ), StandardCharsets.UTF_8);
        String coordinator = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "npc", "breeding", "BreedingPairingCoordinator.java"
        ), StandardCharsets.UTF_8);
        String populationPreparation = Files.readString(
                ACTIONS.resolve("BreedingPairingPopulationPreparationService.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(handoff.contains("LeaseBoundWorldDispatcher.execute("));
        assertTrue(handoff.contains("cancelPrepared("));
        assertTrue(handoff.contains("cancelLocal("));
        assertTrue(prepared.contains("entries.putIfAbsent("));
        assertTrue(prepared.contains("states[index] = UnitState.AMBIGUOUS"));
        assertTrue(coordinator.contains("public PairingResult reserve("));
        assertTrue(coordinator.contains("public PairingResult activate("));
        assertTrue(handoff.contains("cancelOwnedJob("));
        assertTrue(populationPreparation.contains("dispatchNaturalZero("));
        assertTrue(populationPreparation.contains("preparePopulation("));
        assertTrue(populationPreparation.contains(
                "catch (RuntimeException | LinkageError failure)"
        ));
    }

    @Test
    void ambiguousSpawnRetainsApplyingJournalForRecovery() throws Exception {
        String childSpawn = Files.readString(
                ACTIONS.resolve("BreedingPreparedChildSpawnService.java"),
                StandardCharsets.UTF_8
        );
        String execution = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "npc", "breeding", "BreedingJobExecutionService.java"
        ), StandardCharsets.UTF_8
        );

        assertTrue(childSpawn.contains("if (spawn.outcomeAmbiguous())"));
        assertTrue(childSpawn.contains("preparedPopulation.retainAmbiguous("));
        assertTrue(childSpawn.contains("\"breeding_spawn_outcome_ambiguous\""));
        assertTrue(childSpawn.contains("terminalizeSpawnException("));
        assertTrue(childSpawn.contains("catch (RuntimeException | LinkageError failure)"));
        assertTrue(execution.contains("APPLYING journal retained for startup recovery"));
    }
}

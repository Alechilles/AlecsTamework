package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards stable-identity and source-journal ordering across prepared-spawn DB completions. */
class PreparedSpawnCrashBoundaryArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items"
    );
    private static final Path OWNERSHIP = Path.of(
            "src/main/java/com/alechilles/alecstamework/ownership"
    );

    @Test
    void genericContinuationReResolvesBeforeEffectsOrSourceCas() throws Exception {
        String prepared = read(ITEMS, "CompanionPreparedSpawnService.java");
        String continuation = read(ITEMS, "CompanionSpawnCommitContinuation.java");

        int commit = prepared.indexOf("commitLiveQuietly(batch, unitIndex)");
        int resolver = prepared.indexOf("() -> resolveLive(world, batch, unitIndex)", commit);
        int source = prepared.indexOf("live -> invokeSourceFinalization", resolver);
        assertTrue(commit >= 0 && resolver > commit && source > resolver);
        assertTrue(prepared.contains("PlannedCompanionSpawnProbe.probe("));
        assertTrue(prepared.contains("admissionService.isCurrentLiveIdentity("));
        assertTrue(continuation.contains("T live = resolveLive(liveResolver)"));
        assertFalse(continuation.contains("Ref<EntityStore>"));
        assertFalse(continuation.contains("NPCEntity"));
    }

    @Test
    void destructiveSourcesRetainDurableCasEvidenceUntilJournalClose() throws Exception {
        String repository = read(
                Path.of("src/main/java/com/alechilles/alecstamework/persistence/sqlite"),
                "CompanionPopulationRepository.java"
        );
        String journalStore = read(
                Path.of("src/main/java/com/alechilles/alecstamework/persistence/sqlite"),
                "CompanionPopulationJournalStore.java"
        );
        String spawner = read(ITEMS, "SpawnerPreparedSpawnService.java");
        String death = read(ITEMS, "CommandRespawnService.java");
        String lost = read(ITEMS, "CommandRespawnService.java");
        String context = read(OWNERSHIP, "CompanionSpawnSourceFinalizationContext.java");

        assertTrue(repository.contains("journalStore.markApplied(connection, request.operationId())"));
        assertTrue(journalStore.contains("State.APPLYING"));
        assertTrue(journalStore.contains("State.APPLIED"));
        assertTrue(repository.contains("completeSourceFinalizationAsync("));
        assertTrue(context.contains("expectedFingerprint"));
        assertTrue(context.contains("finalizationKey"));
        assertTrue(spawner.contains("Kind.SPAWNER_ITEM"));
        assertTrue(death.contains("Kind.DEATH_RECORD"));
        assertTrue(lost.contains("Kind.LOST_RECORD"));
    }

    @Test
    void coopCompletionCarriesOnlyExactIdentityAcrossCommit() throws Exception {
        String gateway = read(ITEMS, "HytaleManagedCoopReleaseProjectionGateway.java");
        String adapter = read(ITEMS, "ManagedCoopReleaseRuntimeAdapter.java");
        int prepare = gateway.indexOf("populations.prepareAsync(");
        int dispatch = gateway.indexOf("dispatchPrepared(", prepare);
        int claim = adapter.indexOf("populations.claimForSpawn(prepared, claim)");
        int coordinate = adapter.indexOf("orchestrator.coordinate(", claim);
        int spawn = adapter.indexOf("() -> spawnWithPopulation(", coordinate);
        int commit = adapter.indexOf("populations.commitAsync(prepared", spawn);

        assertTrue(prepare >= 0 && dispatch > prepare);
        assertTrue(claim >= 0 && coordinate > claim && spawn > coordinate && commit > spawn);
        assertTrue(adapter.contains("actualTargetUuid"));
        assertFalse(adapter.substring(spawn, commit).contains("Pair<"));
    }

    private static String read(Path root, String file) throws Exception {
        return Files.readString(root.resolve(file));
    }
}

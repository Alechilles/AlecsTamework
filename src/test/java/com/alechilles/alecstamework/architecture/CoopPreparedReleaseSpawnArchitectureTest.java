package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the admission-before-spawn ordering for replacement coop residents. */
class CoopPreparedReleaseSpawnArchitectureTest {
    private static final Path ITEMS = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework", "items"
    );

    @Test
    void managedCoopSystemCannotSpawnAReplacementBeforePopulationAdmission() throws IOException {
        String managed = read("CommandCoopManagedWildCaptureSystem.java");
        String prepared = read("CoopPreparedReleaseSpawnService.java");

        assertFalse(
                managed.contains("npcPlugin.spawnEntity("),
                "Managed coop orchestration must delegate replacement spawning to the prepared service."
        );
        int prepare = prepared.indexOf("admissionService.prepareAsync(");
        int durableLedger = prepared.indexOf("CoopPopulationLedgerMutationJson.release(");
        int claim = prepared.indexOf("admissionService.claimForSpawn(prepared)");
        int spawn = prepared.indexOf("SpawnAttempt attempt = spawn(");
        int preview = prepared.indexOf("preview = previewRelease(");
        int snapshot = prepared.indexOf("applySnapshot(spawned.first(), store, preview)");
        int commit = prepared.indexOf("admissionService.commitAsync(prepared)");
        int ledgerFinalize = prepared.indexOf("coopService.resolveReleaseInPopulationCommit(");
        assertTrue(prepare >= 0 && prepare < claim);
        assertTrue(prepare < durableLedger && durableLedger < claim,
                "The exact coop-slot transition must be embedded in the durable admission journal.");
        assertTrue(claim < spawn, "No denied/recheck-failed release may create a live NPC.");
        assertTrue(prepared.contains("npcPlugin.spawnEntity("),
                "The delegated spawn helper must remain the only live-NPC creation point.");
        assertTrue(spawn < preview && preview < snapshot && snapshot < commit,
                "The source is previewed and restored before durable finalization begins.");
        assertFalse(
                prepared.substring(0, commit).contains("coopService.resolveRelease("),
                "The in-memory coop slot must not be cleared before the atomic SQLite commit."
        );
        assertTrue(commit < ledgerFinalize,
                "The in-memory ledger follows the durable population/ledger transaction.");
    }

    /** Regression: an exception after Store.addEntity cannot safely cancel the coop admission. */
    @Test
    void ambiguousSpawnOutcomeIsProbedAndQuarantinedWithoutCancellation() throws IOException {
        String prepared = read("CoopPreparedReleaseSpawnService.java");

        assertTrue(prepared.contains("PlannedCompanionSpawnProbe.probe("));
        assertTrue(prepared.contains("if (attempt.outcomeAmbiguous())"));
        assertTrue(prepared.contains("coop_release_spawn_outcome_ambiguous"));
        int mismatch = prepared.indexOf("coop_release_spawn_identity_mismatch");
        int commit = prepared.indexOf("admissionService.commitAsync(prepared)");
        assertTrue(mismatch >= 0 && mismatch < commit);
        assertFalse(
                prepared.substring(mismatch, commit).contains(
                        "cancelQuietly(admissionService, prepared"
                ),
                "Once a resident may be live, its APPLYING journal must remain recoverable."
        );
    }

    /** Regression: restoring a coop slot on compensation must remove its physical duplicate. */
    @Test
    void loadedResidentCompensationDespawnsTheMaterializedEntity() throws IOException {
        String sync = read("CommandCoopResidentSyncSystem.java");
        int compensated = sync.indexOf("public void onCompensated(@Nonnull String reason)");
        int despawn = sync.indexOf(
                "despawnReleasedResident(store, currentUuid, npc)", compensated
        );

        assertTrue(compensated >= 0 && despawn > compensated);
        assertTrue(sync.contains("World world = originalStore.getExternalData()"));
        assertTrue(sync.contains("world.getEntityRef(currentUuid)"));
    }

    private static String read(String fileName) throws IOException {
        return Files.readString(ITEMS.resolve(fileName), StandardCharsets.UTF_8);
    }
}

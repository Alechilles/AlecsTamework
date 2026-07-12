package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static contract for the NPCPlugin pre-add and identity-mismatch terminal paths. */
class CompanionPreparedSpawnServiceOrderTest {
    @Test
    void liveUuidIsVerifiedBeforeCommitAndMismatchQuarantinesInsteadOfCanceling() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CompanionPreparedSpawnService.java"
        ));
        int identityCheck = source.indexOf("if (!hasPlannedUuid");
        int mismatchDegrade = source.indexOf(
                "degradeAuthority(\"spawn_live_identity_mismatch\")",
                identityCheck
        );
        int commit = source.indexOf("commitLiveQuietly(batch, unitIndex)", identityCheck);

        assertTrue(identityCheck >= 0);
        assertTrue(mismatchDegrade > identityCheck && mismatchDegrade < commit);
        assertTrue(
                !source.substring(identityCheck, commit).contains(
                        "cancelQuietly(batch, unitIndex, \"spawn-live-identity-mismatch\")"
                ),
                "A possibly live deterministic identity must retain its APPLYING journal."
        );
        assertTrue(commit > identityCheck, "commit must only start after exact UUID confirmation");
    }

    @Test
    void claimAndHolderWriteBothPrecedePhysicalSpawnCompletion() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CompanionPreparedSpawnService.java"
        ));
        int claim = source.indexOf("admissionService.claimForSpawn");
        int spawn = source.indexOf("npcPlugin.spawnEntity");
        int holderWrite = source.indexOf("admissionService.writeSpawnHolder", spawn);

        assertTrue(claim >= 0 && claim < spawn);
        assertTrue(holderWrite > spawn, "planned UUID and owner must be written by the pre-add callback");
    }

    /** Regression: NPCPlugin may throw after Store.addEntity but before returning its Pair. */
    @Test
    void exceptionPathProbesPlannedUuidBeforeChoosingCancellation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CompanionPreparedSpawnService.java"
        ));
        int catchBlock = source.indexOf("catch (RuntimeException | LinkageError failure)");
        int probe = source.indexOf("PlannedCompanionSpawnProbe.probe(", catchBlock);

        assertTrue(catchBlock >= 0 && probe > catchBlock);
        assertTrue(source.contains("if (attempt.outcomeAmbiguous())"));
        assertTrue(source.contains("spawn_entity_outcome_ambiguous"));
    }
}

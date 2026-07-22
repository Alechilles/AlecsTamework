package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the crash boundary where STORING survived but the process-local admission token did not. */
class CommandTimedStorageRestartRecoveryArchitectureTest {
    @Test
    void rosterStorageRecoveryRepreparesItsDurableTransition() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandTimedSummonPopulationPort.java"),
                StandardCharsets.UTF_8);
        int method = source.indexOf("recoverRosterStored(");
        int nextMethod = source.indexOf("private CompletionStage", method);
        String body = source.substring(method, nextMethod);
        assertTrue(body.contains("owner.lifecycleState() != CompanionLifecycleState.STORING"));
        assertTrue(body.contains("prepareTransition(context, owner, PopulationCompanionLifecycle.ROSTER_STORED"));
        assertTrue(body.contains("context.idempotencyKey() + \":stored\""));
        assertTrue(body.contains("admissions.claimForApply"));
        assertTrue(body.contains("admissions.commit"));
    }
}

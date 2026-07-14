package com.alechilles.alecstamework.commands;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkDebugReviveReadyCommandTest {

    @Test
    void reportsPersistenceQuarantineInsteadOfClaimingNoDeadCompanions() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "commands",
                "TameworkDebugReviveReadyCommand.java"
        ), StandardCharsets.UTF_8);

        int preflight = source.indexOf("reportPersistenceBlocked(commandContext, persistence)");
        int mutation = source.indexOf("deathService.markOwnerDeadSnapshotsRespawnReady(playerUuid)");
        int emptyMessage = source.indexOf("No dead linked NPCs were found for your player.");
        int secondHealthCheck = source.lastIndexOf(
                "reportPersistenceBlocked(commandContext, persistence)", emptyMessage
        );

        assertTrue(preflight >= 0 && preflight < mutation);
        assertTrue(secondHealthCheck > mutation && secondHealthCheck < emptyMessage);
        assertTrue(source.contains("health.status() != PersistenceHealthService.Status.DEGRADED"));
        assertTrue(source.contains("+ health.reason() +"));
    }
}

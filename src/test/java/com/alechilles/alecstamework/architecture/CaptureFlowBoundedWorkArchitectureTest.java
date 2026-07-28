package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents probabilistic capture from becoming a tick-time all-profile operation. */
class CaptureFlowBoundedWorkArchitectureTest {
    @Test
    void captureTickWorkUsesIndexedPlayersAndABoundedOrphanSweep() throws Exception {
        String channel = source("items/CaptureChannelVfxSystem.java");
        String handler = source("items/SpawnerFeatureHandler.java");

        assertTrue(channel.contains("Session session = playerUuid == null ? null : ACTIVE.get(playerUuid)"));
        assertTrue(channel.contains("this.query = Query.and(playerType, uuidType)"));
        assertTrue(channel.contains("for (Session session : ACTIVE.values())"));
        assertTrue(channel.contains("shouldSweepOrphanedSession("));
        assertTrue(channel.contains("if (world == null || ACTIVE.isEmpty())"));
        assertTrue(channel.contains("ACTIVE.remove(session.playerUuid, session)"));
        assertFalse(channel.contains("OwnerPopulationIndex"));
        assertFalse(channel.contains("NpcProfileRepository"));
        assertFalse(channel.contains("loadAll"));
        assertFalse(channel.contains("Universe.get().getPlayers"));

        assertFalse(handler.contains("implements TickingSystem"));
        assertFalse(handler.contains("loadAllProfiles"));
        assertFalse(handler.contains("Universe.get().getPlayers"));
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/" + relative.replace('/',
                        java.io.File.separatorChar))).replace("\r\n", "\n");
    }
}

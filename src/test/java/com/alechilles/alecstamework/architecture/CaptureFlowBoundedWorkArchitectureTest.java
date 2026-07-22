package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents probabilistic capture from becoming a tick-time all-profile operation. */
class CaptureFlowBoundedWorkArchitectureTest {
    @Test
    void captureTickWorkIsBoundedToActiveChannelSessions() throws Exception {
        String channel = source("items/CaptureChannelVfxSystem.java");
        String handler = source("items/SpawnerFeatureHandler.java");
        String attempts = source("items/SpawnerCaptureAttemptRuntimeCoordinator.java");

        assertTrue(channel.contains("for (Session session : ACTIVE.values())"));
        assertTrue(channel.contains("if (world == null || ACTIVE.isEmpty())"));
        assertTrue(channel.contains("ACTIVE.remove(session.playerUuid, session)"));
        assertFalse(channel.contains("OwnerPopulationIndex"));
        assertFalse(channel.contains("NpcProfileRepository"));
        assertFalse(channel.contains("loadAll"));
        assertFalse(channel.contains("Universe.get().getPlayers"));

        assertTrue(attempts.contains(
                "ConcurrentHashMap<UUID, CaptureAttemptHandle> channelAttempts"));
        assertTrue(attempts.contains("channelAttempts.put(playerUuid, attempt)"));
        assertTrue(attempts.contains("channelAttempts.remove(playerUuid)"));
        assertFalse(handler.contains("implements TickingSystem"));
        assertFalse(attempts.contains("implements TickingSystem"));
        assertFalse(handler.contains("loadAllProfiles"));
        assertFalse(attempts.contains("loadAllProfiles"));
        assertFalse(handler.contains("Universe.get().getPlayers"));
        assertFalse(attempts.contains("Universe.get().getPlayers"));
    }

    @Test
    void everyCaptureEntryPointCarriesOnePreparedHandleIntoTerminalResolution() throws Exception {
        String handler = source("items/SpawnerFeatureHandler.java");
        String attempts = source("items/SpawnerCaptureAttemptRuntimeCoordinator.java");
        String owner = source("npc/actions/ActionTameworkCaptureOwner.java");
        String wild = source("npc/actions/ActionTameworkCaptureWild.java");

        assertTrue(handler.contains("CaptureAttemptHandle attempt = prepareCaptureAttempt("));
        assertTrue(handler.contains("captureAttemptRuntime.rememberChannel(playerUuid.getUuid(), attempt)"));
        assertTrue(handler.contains(
                "CaptureAttemptHandle attempt = player == null\n"
                        + "                ? null : captureAttemptRuntime.takeChannel(player.getUuid())"));
        assertTrue(handler.contains("missing-channel-attempt-identity"));
        assertTrue(attempts.contains("attempts.resolve(request)"));
        assertFalse(handler.contains("attemptId == null ? UUID.randomUUID() : attemptId"));
        assertFalse(attempts.contains("attemptId == null ? UUID.randomUUID() : attemptId"));

        assertPreparedHandleFlowsOnce(owner);
        assertPreparedHandleFlowsOnce(wild);
    }

    private static void assertPreparedHandleFlowsOnce(String action) {
        int prepare = action.indexOf(
                "CaptureAttemptHandle attempt = handler.prepareCaptureAttempt(player, itemStack, null)");
        int require = action.indexOf("attempt != null", prepare);
        int dispatch = action.indexOf(
                "handler.captureFromNpcAction(player, npcRef, itemStack, config, attempt)", require);
        assertTrue(prepare >= 0);
        assertTrue(require > prepare);
        assertTrue(dispatch > require);
        assertFalse(action.contains("UUID.randomUUID()"));
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/" + relative.replace('/',
                        java.io.File.separatorChar)));
    }
}

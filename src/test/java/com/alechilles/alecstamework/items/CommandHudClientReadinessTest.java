package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.entity.entities.Player;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Verifies that command HUD packets wait for the gameplay-ready client state. */
class CommandHudClientReadinessTest {
    @Test
    void joinCandidateSurvivesClientLoadingWithoutAnInventoryChange() {
        var tracker = new CommandTargetHudActivationTracker();
        var playerId = java.util.UUID.randomUUID();
        tracker.markDirty(playerId);
        Assertions.assertTrue(tracker.selectCandidateBatch(1).playerUuids().contains(playerId));
        Assertions.assertFalse(CommandHudClientReadiness.canRenderOrRetry(
                allocate(WaitingPlayer.class), tracker, null, playerId));
        Assertions.assertTrue(tracker.selectCandidateBatch(1).playerUuids().contains(playerId));
        Assertions.assertTrue(CommandHudClientReadiness.canRenderOrRetry(
                allocate(ReadyPlayer.class), tracker, null, playerId));
        tracker.recordResolvedHand(playerId, "Flute", true, 1_000L);
        Assertions.assertEquals("Flute", tracker.cachedCommandItemId(playerId));
        tracker.remove(playerId);
        Assertions.assertTrue(tracker.selectCandidateBatch(1).playerUuids().isEmpty());
    }

    @Test
    void waitingClientCannotReceiveCommandHud() {
        Assertions.assertFalse(CommandHudClientReadiness.canRender(
                allocate(WaitingPlayer.class)
        ));
    }

    @Test
    void gameplayReadyClientCanReceiveCommandHud() {
        Assertions.assertTrue(CommandHudClientReadiness.canRender(
                allocate(ReadyPlayer.class)
        ));
    }

    private static final class WaitingPlayer extends Player {
        @Override
        public boolean isWaitingForClientReady() {
            return true;
        }
    }

    private static final class ReadyPlayer extends Player {
        @Override
        public boolean isWaitingForClientReady() {
            return false;
        }
    }

    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}

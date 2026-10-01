package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandRestorationResultFeedbackTest {
    private static final String P = "tamework.ui.notifications.command.";

    @Test
    void everyRestoreResultHasItsMessage() {
        assertEquals(P + "respawn.success", CommandRestorationCompletionListener.keyFor(RestoreFlow.Result.RESTORED));
        assertEquals(P + "shared.cooldown", CommandRestorationCompletionListener.keyFor(RestoreFlow.Result.COOLDOWN));
        assertEquals(P + "respawn.notDeadOrLost", CommandRestorationCompletionListener.keyFor(RestoreFlow.Result.NOT_ALLOWED));
        assertEquals(P + "respawn.notDeadOrLost", CommandRestorationCompletionListener.keyFor(RestoreFlow.Result.NOT_FOUND));
        assertEquals(P + "respawn.recoverFailed", CommandRestorationCompletionListener.keyFor(RestoreFlow.Result.NO_SNAPSHOT));
        assertEquals(P + "respawn.recoverFailed", CommandRestorationCompletionListener.keyFor(RestoreFlow.Result.SPAWN_FAILED));
        assertEquals(P + "respawn.unavailable", CommandRestorationCompletionListener.keyFor(RestoreFlow.Result.COMMIT_FAILED));
        assertEquals(P + "respawn.unavailable", CommandRestorationCompletionListener.keyFor(RestoreFlow.Result.CONFLICT));
    }
}

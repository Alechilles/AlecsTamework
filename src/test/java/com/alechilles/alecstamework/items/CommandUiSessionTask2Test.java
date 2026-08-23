package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiAction;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for session lifecycle and opaque action authority. */
class CommandUiSessionTask2Test {
    @Test
    void genericAndBondedHandlesRouteSeparatelyAndRecheckAuthority() {
        UUID sessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                snapshot(sessionId, 4L, 1L), gateway);
        CommandUiSessionImpl other = new CommandUiSessionImpl(
                snapshot(otherSessionId, 1L, 1L), gateway);
        AtomicInteger genericCalls = new AtomicInteger();
        AtomicInteger bondedCalls = new AtomicInteger();
        AtomicBoolean genericAuthority = new AtomicBoolean(true);

        var genericHandle = session.issueGeneric(
                new CommandUiAction("UNLINK", UUID.randomUUID()),
                genericAuthority::get,
                () -> {
                    genericCalls.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                }, false);
        var bondedHandle = session.issueBonded(
                new CommandUiAction("SUMMON", UUID.randomUUID()),
                () -> true,
                () -> {
                    bondedCalls.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                }, false);

        assertEquals(CommandUiActionStatus.APPLIED,
                session.invoke(genericHandle).toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.APPLIED,
                session.invoke(bondedHandle).toCompletableFuture().join().status());
        assertEquals(1, genericCalls.get());
        assertEquals(1, bondedCalls.get());

        var forged = other.invoke(genericHandle).toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.DENIED, forged.status());

        var staleHandle = other.issueGeneric(
                new CommandUiAction("RECALL", UUID.randomUUID()),
                () -> true,
                () -> java.util.concurrent.CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()), false);
        other.publish(snapshot(otherSessionId, 2L, 2L), CommandUiChangeSet.full());
        assertEquals(CommandUiActionStatus.STALE,
                other.invoke(staleHandle).toCompletableFuture().join().status());

        genericAuthority.set(false);
        var deniedHandle = session.issueGeneric(
                new CommandUiAction("CULL", UUID.randomUUID()),
                genericAuthority::get,
                () -> java.util.concurrent.CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()), false);
        assertEquals(CommandUiActionStatus.DENIED,
                session.invoke(deniedHandle).toCompletableFuture().join().status());

        session.close();
        assertTrue(session.isClosed());
        assertEquals(CommandUiActionStatus.STALE,
                session.invoke(bondedHandle).toCompletableFuture().join().status());
        assertFalse(session.requestRefresh());
    }

    @Test
    void confirmationDoesNotMutateBeforeFreshConfirmationAndIsSingleUse() {
        UUID sessionId = UUID.randomUUID();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                snapshot(sessionId, 1L, 7L), gateway);
        AtomicInteger mutations = new AtomicInteger();
        var handle = session.issueGeneric(
                new CommandUiAction("ABANDON", UUID.randomUUID(), null, true),
                () -> true,
                () -> {
                    mutations.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                }, true);

        var first = session.invoke(handle).toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED, first.status());
        assertNotNull(first.confirmationHandle());
        assertEquals(0, mutations.get());

        var applied = session.invoke(first.confirmationHandle())
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.APPLIED, applied.status());
        assertEquals(1, mutations.get());
        assertEquals(CommandUiActionStatus.STALE,
                session.invoke(first.confirmationHandle()).toCompletableFuture().join().status());
    }

    @Test
    void presentationRevisionAdvancesWithoutChangingActionGeneration() {
        UUID sessionId = UUID.randomUUID();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                snapshot(sessionId, 3L, 9L), gateway);
        session.publish(snapshot(sessionId, 3L, 9L), CommandUiChangeSet.full());

        assertEquals(4L, session.snapshot().presentationRevision());
        assertEquals(9L, session.snapshot().actionGeneration());
    }

    private static CommandUiSnapshot snapshot(
            UUID sessionId,
            long presentationRevision,
            long actionGeneration
    ) {
        return new CommandUiSnapshot(sessionId, presentationRevision,
                actionGeneration, null, List.of(), List.of(),
                new CommandUiPanelState("linked"));
    }
}

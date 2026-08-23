package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for session lifecycle, dispatch, and opaque action authority. */
class CommandUiSessionTask2Test {
    @Test
    void genericAndBondedHandlesUseSeparateSessionRoutesAndRecheckAuthority() {
        UUID genericId = UUID.randomUUID();
        UUID bondedId = UUID.randomUUID();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        CommandUiSessionImpl generic = session(genericId,
                CommandUiSessionImpl.Mode.GENERIC, gateway, 1L);
        CommandUiSessionImpl bonded = session(bondedId,
                CommandUiSessionImpl.Mode.BONDED, gateway, 1L);
        AtomicInteger genericCalls = new AtomicInteger();
        AtomicInteger bondedCalls = new AtomicInteger();
        AtomicBoolean genericAuthority = new AtomicBoolean(true);

        var genericHandle = generic.issueGeneric(
                new CommandUiAction("UNLINK", UUID.randomUUID()),
                genericAuthority::get,
                () -> {
                    genericCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                }, false);
        var bondedHandle = bonded.issueBonded(
                new CommandUiAction("SUMMON", UUID.randomUUID()),
                () -> true,
                () -> {
                    bondedCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                }, false);

        assertEquals(CommandUiActionStatus.APPLIED,
                generic.invoke(genericHandle).toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.DENIED,
                generic.invoke(bondedHandle).toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.APPLIED,
                bonded.invoke(bondedHandle).toCompletableFuture().join().status());
        assertEquals(1, genericCalls.get());
        assertEquals(1, bondedCalls.get());

        genericAuthority.set(false);
        var deniedHandle = generic.issueGeneric(
                new CommandUiAction("CULL", UUID.randomUUID()),
                genericAuthority::get,
                () -> CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()), false);
        assertEquals(CommandUiActionStatus.DENIED,
                generic.invoke(deniedHandle).toCompletableFuture().join().status());

        generic.close();
        assertTrue(generic.isClosed());
        assertEquals(CommandUiActionStatus.STALE,
                generic.invoke(genericHandle).toCompletableFuture().join().status());
        assertFalse(generic.requestRefresh());
        bonded.close();
    }

    @Test
    void confirmationDoesNotMutateBeforeFreshConfirmationAndIsSingleUse() {
        UUID sessionId = UUID.randomUUID();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        CommandUiSessionImpl session = session(sessionId,
                CommandUiSessionImpl.Mode.GENERIC, gateway, 7L);
        AtomicInteger mutations = new AtomicInteger();
        var handle = session.issueGeneric(
                new CommandUiAction("ABANDON", UUID.randomUUID(), null, true),
                () -> true,
                () -> {
                    mutations.incrementAndGet();
                    return CompletableFuture.completedFuture(
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
        session.close();
    }

    @Test
    void providerSinkCannotReplaceSnapshotAndOnlySubmitsHostWork() {
        UUID sessionId = UUID.randomUUID();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        AtomicBoolean submitted = new AtomicBoolean();
        CommandUiSnapshot initial = snapshot(sessionId, 3L, 9L);
        CommandUiSessionImpl session = new CommandUiSessionImpl(
                sessionId, initial, gateway, CommandUiWorldDispatcher.direct(),
                CommandUiSessionImpl.Mode.GENERIC, () -> { }, ignored -> { },
                (commands, events, clear) -> {
                    submitted.set(!clear);
                    return true;
                });

        assertTrue(session.updateSink().submit(
                new UICommandBuilder(), new UIEventBuilder(), false));
        assertTrue(submitted.get());
        assertEquals(initial, session.snapshot());
        session.close();
    }

    @Test
    void invokeAndRefreshRunOnRequiredDispatcherThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "command-ui-world-test"));
        try {
            UUID sessionId = UUID.randomUUID();
            CommandUiActionGateway gateway = new CommandUiActionGateway();
            AtomicReference<String> authorityThread = new AtomicReference<>();
            AtomicReference<String> mutationThread = new AtomicReference<>();
            AtomicReference<String> refreshThread = new AtomicReference<>();
            CommandUiSessionImpl session = new CommandUiSessionImpl(
                    sessionId, snapshot(sessionId, 1L, 1L), gateway,
                    CommandUiWorldDispatcher.executor(executor),
                    CommandUiSessionImpl.Mode.GENERIC,
                    () -> refreshThread.set(Thread.currentThread().getName()),
                    ignored -> { }, null);
            var handle = session.issueGeneric(new CommandUiAction("MANAGE_GROUPS"),
                    () -> {
                        authorityThread.set(Thread.currentThread().getName());
                        return true;
                    }, () -> {
                        mutationThread.set(Thread.currentThread().getName());
                        return CompletableFuture.completedFuture(
                                CommandUiActionResult.applied());
                    }, false);

            assertEquals(CommandUiActionStatus.APPLIED,
                    session.invoke(handle).toCompletableFuture().join().status());
            assertTrue(session.requestRefresh());
            executor.submit(() -> { }).get();
            assertNotEquals(Thread.currentThread().getName(), authorityThread.get());
            assertEquals("command-ui-world-test", authorityThread.get());
            assertEquals("command-ui-world-test", mutationThread.get());
            assertEquals("command-ui-world-test", refreshThread.get());
            session.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void genericBinderReportsMissingTargetAndAuthorityDenial() {
        CommandSelectionPageService service =
                new CommandSelectionPageService(null, null, null, null, null);
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        UUID sessionId = UUID.randomUUID();
        CommandUiSessionImpl session = session(sessionId,
                CommandUiSessionImpl.Mode.GENERIC, gateway, 1L);
        CommandSelectionPageService.Actions callbacks = new CommandSelectionPageService.Actions(
                ignored -> { }, ignored -> { }, ignored -> { }, ignored -> { },
                ignored -> { }, ignored -> { }, ignored -> { }, ignored -> { },
                () -> { }, () -> { }, ignored -> { });

        var missing = service.bindGenericUiAction(session,
                new CommandUiAction("UNLINK", UUID.randomUUID()), callbacks,
                () -> true, ignored -> false, false);
        assertEquals(CommandUiActionStatus.NOT_FOUND,
                session.invoke(missing).toCompletableFuture().join().status());

        var denied = service.bindGenericUiAction(session,
                new CommandUiAction("UNLINK", UUID.randomUUID()), callbacks,
                () -> false, ignored -> true, false);
        assertEquals(CommandUiActionStatus.DENIED,
                session.invoke(denied).toCompletableFuture().join().status());
        session.close();
    }

    @Test
    void consumedAndOlderGenerationHandlesAreRetired() {
        UUID sessionId = UUID.randomUUID();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        CommandUiSessionImpl session = session(sessionId,
                CommandUiSessionImpl.Mode.GENERIC, gateway, 0L);
        var first = session.issueGeneric(new CommandUiAction("MANAGE_GROUPS"),
                () -> true, () -> CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()), false);
        assertEquals(CommandUiActionStatus.APPLIED,
                session.invoke(first).toCompletableFuture().join().status());
        assertEquals(0, gateway.activeHandleCount());

        for (long generation = 1L; generation <= 64L; generation++) {
            session.publishInternal(snapshot(sessionId, generation, generation),
                    CommandUiChangeSet.empty());
            session.issueGeneric(new CommandUiAction("MANAGE_GROUPS"),
                    () -> true, () -> CompletableFuture.completedFuture(
                            CommandUiActionResult.applied()), false);
            assertTrue(gateway.activeHandleCount() <= 1);
        }
        session.close();
    }

    @Test
    void sameGenerationHandleStorageRemainsBounded() {
        UUID sessionId = UUID.randomUUID();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        CommandUiSessionImpl session = session(sessionId,
                CommandUiSessionImpl.Mode.GENERIC, gateway, 3L);
        var first = session.issueGeneric(new CommandUiAction("MANAGE_GROUPS"),
                () -> true, () -> CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()), false);

        for (int index = 0; index < 400; index++) {
            session.issueGeneric(new CommandUiAction(
                            "MANAGE_GROUPS", UUID.nameUUIDFromBytes(
                                    ("target-" + index).getBytes(
                                            StandardCharsets.UTF_8))),
                    () -> true, () -> CompletableFuture.completedFuture(
                            CommandUiActionResult.applied()), false);
        }

        assertTrue(gateway.activeHandleCount() <= 256);
        assertEquals(CommandUiActionStatus.STALE,
                session.invoke(first).toCompletableFuture().join().status());
        session.close();
    }

    private static CommandUiSessionImpl session(
            UUID sessionId,
            CommandUiSessionImpl.Mode mode,
            CommandUiActionGateway gateway,
            long generation
    ) {
        return new CommandUiSessionImpl(
                sessionId, snapshot(sessionId, 1L, generation), gateway,
                CommandUiWorldDispatcher.direct(), mode);
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

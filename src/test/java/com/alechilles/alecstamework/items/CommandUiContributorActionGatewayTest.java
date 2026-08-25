package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionRequest;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorActionContext;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production behavior contract for contributor-owned command UI actions. */
class CommandUiContributorActionGatewayTest {
    @Test
    void validPageAndRowActionsReceiveStableContextOnTheCurrentWorld() {
        UUID sessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        UUID companionId = UUID.randomUUID();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        AtomicReference<CommandUiContributorActionContext> pageContext =
                new AtomicReference<>();
        AtomicReference<CommandUiContributorActionContext> rowContext =
                new AtomicReference<>();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        gateway.openSession(sessionId, 12L);
        CommandUiActionGateway.ContributorIdentity identity =
                new CommandUiActionGateway.ContributorIdentity(
                        playerId, "config.test", rowId, companionId, "profile-7");
        CommandUiContributorActionBinding page = binding(contributorId, 4L,
                CommandUiContributorAction.Scope.PAGE, null,
                CommandUiContributorAction.InputPolicy.NONE,
                context -> {
                    pageContext.set(context);
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                });
        CommandUiContributorActionBinding row = binding(contributorId, 4L,
                CommandUiContributorAction.Scope.ROW, rowId,
                CommandUiContributorAction.InputPolicy.NONE,
                context -> {
                    rowContext.set(context);
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                });

        CommandUiActionHandle pageHandle = gateway.issueContributor(
                sessionId, page, 8L, identity, () -> true, context -> true);
        CommandUiActionHandle rowHandle = gateway.issueContributor(
                sessionId, row, 8L, identity, () -> true, context -> true);

        assertEquals(CommandUiActionStatus.APPLIED,
                gateway.invoke(sessionId, CommandUiActionRequest.of(pageHandle),
                        8L, null).toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.APPLIED,
                gateway.invoke(sessionId, CommandUiActionRequest.of(rowHandle),
                        8L, null).toCompletableFuture().join().status());
        assertEquals(sessionId, pageContext.get().sessionId());
        assertEquals(playerId, pageContext.get().playerUuid());
        assertEquals("config.test", pageContext.get().configId());
        assertNull(pageContext.get().rowId());
        assertEquals(rowId, rowContext.get().rowId());
        assertEquals(companionId, rowContext.get().companionId());
        assertEquals("profile-7", rowContext.get().profileId());
        assertFalse(pageContext.get().confirmed());
    }

    @Test
    void forgedWrongSessionAndStaleGenerationsNeverCallTheHandler() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        CommandUiContributorActionBinding binding = binding(contributorId, 3L,
                CommandUiContributorAction.Scope.PAGE, null,
                CommandUiContributorAction.InputPolicy.NONE,
                context -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                });
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        gateway.openSession(first, 9L);
        gateway.openSession(second, 9L);
        CommandUiActionHandle handle = gateway.issueContributor(
                first, binding, 7L, identity(), () -> true, context -> true);

        assertEquals(CommandUiActionStatus.DENIED,
                gateway.invoke(second, CommandUiActionRequest.of(handle),
                        7L, null).toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.STALE,
                gateway.invoke(first, new CommandUiActionRequest(
                                new CommandUiActionHandle("forged"), (String) null),
                        7L, null).toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.STALE,
                gateway.invoke(first, CommandUiActionRequest.of(handle),
                        8L, null).toCompletableFuture().join().status());

        AtomicBoolean contributorActive = new AtomicBoolean(false);
        CommandUiActionHandle staleContributor = gateway.issueContributor(
                first, binding, 8L, identity(), contributorActive::get,
                context -> true);
        assertEquals(CommandUiActionStatus.STALE,
                gateway.invoke(first, CommandUiActionRequest.of(staleContributor),
                        8L, null).toCompletableFuture().join().status());
        assertEquals(0, calls.get());
    }

    @Test
    void invisibleOrDisabledContributorActionsDoNotReceiveHandles() {
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        AtomicInteger calls = new AtomicInteger();
        CommandUiContributorAction invisible = new CommandUiContributorAction(
                "hidden", "HIDDEN", "Hidden", null, false, true, null,
                CommandUiContributorAction.InputPolicy.NONE, false, Map.of(),
                context -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                });
        CommandUiContributorAction disabled = new CommandUiContributorAction(
                "disabled", "DISABLED", "Disabled", null, true, false,
                "Not available", CommandUiContributorAction.InputPolicy.NONE,
                false, Map.of(), context -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                });
        assertNull(invisible.view(null));
        assertNull(disabled.view(new CommandUiActionHandle("never"))
                .handle());
        assertEquals(0, calls.get());
    }

    @Test
    void everyContributorInputBoundIsCheckedBeforeAuthorityAndHandler() {
        AtomicInteger authority = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        UUID sessionId = UUID.randomUUID();
        gateway.openSession(sessionId, 1L);
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        CommandUiContributorActionBinding binding = binding(contributorId, 1L,
                CommandUiContributorAction.Scope.PAGE, null,
                CommandUiContributorAction.InputPolicy.OPTIONAL,
                context -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                });
        CommandUiActionGateway.ContributorIdentity identity = identity();

        assertInputDeniedBeforeAuthority(gateway, sessionId, binding, identity,
                nestedValue(5), authority, calls);
        assertInputDeniedBeforeAuthority(gateway, sessionId, binding, identity,
                manyNodesValue(), authority, calls);
        assertInputDeniedBeforeAuthority(gateway, sessionId, binding, identity,
                listOfSize(33), authority, calls);
        assertInputDeniedBeforeAuthority(gateway, sessionId, binding, identity,
                objectWithKeyLength(65), authority, calls);
        assertInputDeniedBeforeAuthority(gateway, sessionId, binding, identity,
                objectWithTotalCharacters(4097), authority, calls);
        assertEquals(0, authority.get());
        assertEquals(0, calls.get());
    }

    @Test
    void confirmationExpiresIsOneUseAndRechecksAuthority() {
        AtomicReference<Long> now = new AtomicReference<>(100L);
        CommandUiActionGateway gateway = new CommandUiActionGateway(now::get);
        UUID sessionId = UUID.randomUUID();
        gateway.openSession(sessionId, 2L);
        AtomicBoolean authority = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> confirmed = new AtomicReference<>();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        CommandUiContributorActionBinding binding = binding(contributorId, 1L,
                CommandUiContributorAction.Scope.PAGE, null,
                CommandUiContributorAction.InputPolicy.NONE,
                context -> {
                    calls.incrementAndGet();
                    confirmed.set(context.confirmed());
                    return CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                }, true);
        CommandUiActionHandle initial = gateway.issueContributor(
                sessionId, binding, 4L, identity(), () -> true,
                context -> authority.get());
        CommandUiActionResult requested = gateway.invoke(sessionId,
                CommandUiActionRequest.of(initial), 4L, null)
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED,
                requested.status());
        assertEquals(0, calls.get());
        assertNotNull(requested.confirmationHandle());

        authority.set(false);
        assertEquals(CommandUiActionStatus.DENIED,
                gateway.invoke(sessionId, CommandUiActionRequest.of(
                                requested.confirmationHandle()), 4L, null)
                        .toCompletableFuture().join().status());
        assertEquals(0, calls.get());

        authority.set(true);
        CommandUiActionResult retry = gateway.invoke(sessionId,
                CommandUiActionRequest.of(initial), 4L, null)
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED, retry.status());
        now.set(now.get() + CommandUiActionGateway.CONFIRMATION_LIFETIME.toNanos()
                + 1L);
        assertEquals(CommandUiActionStatus.STALE,
                gateway.invoke(sessionId, CommandUiActionRequest.of(
                                retry.confirmationHandle()), 4L, null)
                        .toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED,
                gateway.invoke(sessionId, CommandUiActionRequest.of(initial), 4L,
                                null)
                        .toCompletableFuture().join().status());

        now.set(100L);
        CommandUiActionResult last = gateway.invoke(sessionId,
                CommandUiActionRequest.of(initial), 4L, null)
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED, last.status());
        CommandUiActionResult sibling = gateway.invoke(sessionId,
                CommandUiActionRequest.of(initial), 4L, null)
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED,
                sibling.status());
        CommandUiActionResult applied = gateway.invoke(sessionId,
                CommandUiActionRequest.of(last.confirmationHandle()), 4L, null)
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.APPLIED, applied.status());
        assertEquals(Boolean.TRUE, confirmed.get());
        assertEquals(1, calls.get());
        assertEquals(CommandUiActionStatus.STALE,
                gateway.invoke(sessionId, CommandUiActionRequest.of(
                                last.confirmationHandle()), 4L, null)
                        .toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.STALE,
                gateway.invoke(sessionId, CommandUiActionRequest.of(
                                sibling.confirmationHandle()), 4L, null)
                        .toCompletableFuture().join().status());
        assertEquals(CommandUiActionStatus.STALE,
                gateway.invoke(sessionId, CommandUiActionRequest.of(initial), 4L,
                                null)
                        .toCompletableFuture().join().status());
    }

    @Test
    void handlerExceptionNullStageAndIncompleteStageFailWithoutClosingSession() {
        CommandUiActionGateway gateway = new CommandUiActionGateway();
        UUID sessionId = UUID.randomUUID();
        gateway.openSession(sessionId, 1L);
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        List<CommandUiContributorActionHandlerFactory> factories = List.of(
                () -> context -> { throw new IllegalStateException("boom"); },
                () -> context -> null,
                () -> context -> CompletableFuture.completedFuture(null),
                () -> context -> new CompletableFuture<>());
        for (CommandUiContributorActionHandlerFactory factory : factories) {
            CommandUiContributorActionBinding binding = binding(contributorId, 1L,
                    CommandUiContributorAction.Scope.PAGE, null,
                    CommandUiContributorAction.InputPolicy.NONE,
                    factory.create());
            CommandUiActionHandle handle = gateway.issueContributor(
                    sessionId, binding, 1L, identity(), () -> true,
                    context -> true);
            assertEquals(CommandUiActionStatus.FAILED,
                    gateway.invoke(sessionId, CommandUiActionRequest.of(handle),
                            1L, null).toCompletableFuture().join().status());
        }
        assertTrue(gateway.sessionActive(sessionId));
    }

    private static void assertInputDeniedBeforeAuthority(
            CommandUiActionGateway gateway,
            UUID sessionId,
            CommandUiContributorActionBinding binding,
            CommandUiActionGateway.ContributorIdentity identity,
            CommandUiValue value,
            AtomicInteger authority,
            AtomicInteger calls
    ) {
        CommandUiActionHandle handle = gateway.issueContributor(
                sessionId, binding, 1L, identity, () -> true, context -> {
                    authority.incrementAndGet();
                    return true;
                });
        assertEquals(CommandUiActionStatus.DENIED,
                gateway.invoke(sessionId, CommandUiActionRequest.withInput(handle,
                                value), 1L, null)
                        .toCompletableFuture().join().status());
        assertEquals(0, calls.get());
    }

    private static CommandUiContributorActionBinding binding(
            CommandUiContributorId contributorId,
            long generation,
            CommandUiContributorAction.Scope scope,
            UUID rowId,
            CommandUiContributorAction.InputPolicy inputPolicy,
            com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler handler
    ) {
        return binding(contributorId, generation, scope, rowId, inputPolicy,
                handler, false);
    }

    private static CommandUiContributorActionBinding binding(
            CommandUiContributorId contributorId,
            long generation,
            CommandUiContributorAction.Scope scope,
            UUID rowId,
            CommandUiContributorAction.InputPolicy inputPolicy,
            com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler handler,
            boolean confirmation
    ) {
        return new CommandUiContributorActionBinding(contributorId, generation,
                new CommandUiContributorAction(
                        "action-" + UUID.randomUUID(), "TEST", "Test action",
                        null, true, true, null, inputPolicy, confirmation,
                        Map.of(), handler), scope, rowId);
    }

    private static CommandUiActionGateway.ContributorIdentity identity() {
        return new CommandUiActionGateway.ContributorIdentity(
                UUID.randomUUID(), "config.test", null, null, null);
    }

    private static CommandUiValue nestedValue(int depth) {
        CommandUiValue value = CommandUiValue.string("x");
        for (int index = 0; index < depth; index++) {
            value = CommandUiValue.list(List.of(value));
        }
        return value;
    }

    private static CommandUiValue listOfSize(int size) {
        List<CommandUiValue> values = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            values.add(CommandUiValue.string("x"));
        }
        return CommandUiValue.list(values);
    }

    private static CommandUiValue manyNodesValue() {
        return manyNodesBranch(3);
    }

    private static CommandUiValue manyNodesBranch(int remainingDepth) {
        if (remainingDepth == 0) return CommandUiValue.string("x");
        return CommandUiValue.list(List.of(
                manyNodesBranch(remainingDepth - 1),
                manyNodesBranch(remainingDepth - 1),
                manyNodesBranch(remainingDepth - 1),
                manyNodesBranch(remainingDepth - 1)));
    }

    private static CommandUiValue objectWithKeyLength(int length) {
        return CommandUiValue.object(Map.of("k".repeat(length),
                CommandUiValue.string("x")));
    }

    private static CommandUiValue objectWithTotalCharacters(int total) {
        return CommandUiValue.object(Map.of("key", CommandUiValue.string(
                "x".repeat(total - 3))));
    }

    @FunctionalInterface
    private interface CommandUiContributorActionHandlerFactory {
        com.alechilles.alecstamework.api.commandui.CommandUiContributorActionHandler create();
    }
}

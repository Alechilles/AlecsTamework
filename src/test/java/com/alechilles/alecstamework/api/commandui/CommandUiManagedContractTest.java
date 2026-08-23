package com.alechilles.alecstamework.api.commandui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Compatibility and detachment behavior for managed command UI flows. */
class CommandUiManagedContractTest {
    @Test
    void legacySessionAcceptsHandleOnlyRequestsButRejectsTextInput() {
        CommandUiActionHandle handle = new CommandUiActionHandle("issued-token");
        LegacySession session = new LegacySession();

        CommandUiActionResult accepted = session.invoke(
                new CommandUiActionRequest(handle, null))
                .toCompletableFuture().join();
        CommandUiActionResult denied = session.invoke(
                new CommandUiActionRequest(handle, "injected"))
                .toCompletableFuture().join();

        assertEquals(CommandUiActionStatus.ACCEPTED, accepted.status());
        assertSame(handle, session.lastHandle.get());
        assertEquals(CommandUiActionStatus.DENIED, denied.status());
    }

    @Test
    void managedFlowResultKeepsAnImmutableDetachedView() {
        CommandUiActionView action = new CommandUiActionView(
                "CREATE_GROUP", "Create", true, null, false,
                new CommandUiActionHandle("create-token"));
        CommandUiGroupView group = new CommandUiGroupView(
                "barn", "Barn", "#445566", true,
                action, action, action, action);
        CommandUiGroupFlowView flow = new CommandUiGroupFlowView(
                "barn", List.of(group), action, Map.of("scope", "tool"));

        CommandUiActionResult result = CommandUiActionResult.presented(flow);

        assertSame(flow, result.flowView());
        assertFalse(result.refreshSnapshot());
        assertEquals("groups", flow.kind());
        assertThrows(UnsupportedOperationException.class,
                () -> flow.groups().add(group));
        assertThrows(UnsupportedOperationException.class,
                () -> flow.metadata().put("other", "value"));
    }

    private static final class LegacySession implements CommandUiSession {
        private final UUID sessionId = UUID.randomUUID();
        private final AtomicReference<CommandUiActionHandle> lastHandle =
                new AtomicReference<>();

        @Override
        public UUID sessionId() {
            return sessionId;
        }

        @Override
        public CommandUiSnapshot snapshot() {
            return new CommandUiSnapshot(sessionId, 1L, null,
                    List.of(), List.of(), new CommandUiPanelState("linked"));
        }

        @Override
        public CompletionStage<CommandUiActionResult> invoke(
                CommandUiActionHandle handle) {
            lastHandle.set(handle);
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.accepted());
        }

        @Override
        public boolean requestRefresh() {
            return false;
        }

        @Override
        public void close(CommandUiCloseReason reason) {
        }
    }
}

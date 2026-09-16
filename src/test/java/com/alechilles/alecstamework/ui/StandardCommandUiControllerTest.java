package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdateSink;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Standard controller behavior while the legacy renderer is migrated. */
class StandardCommandUiControllerTest {
    @Test
    void delegatesBuildEventsAndBackgroundPartialUpdatesThroughTheSession() {
        RecordingDelegate delegate = new RecordingDelegate();
        RecordingSession session = new RecordingSession();
        StandardCommandUiController controller =
                new StandardCommandUiController(delegate);
        UICommandBuilder initialCommands = new UICommandBuilder();
        UIEventBuilder initialEvents = new UIEventBuilder();
        CommandSelectionEventData event = new CommandSelectionEventData();

        controller.buildInitial(new CommandUiOpenContext(), session,
                session.snapshot(), null, null, initialCommands, initialEvents);
        controller.handleEvent(event, session, session.snapshot(), null, null,
                new UICommandBuilder(), new UIEventBuilder());
        delegate.sendBackgroundUpdate();
        controller.close();
        controller.close();

        assertSame(initialCommands, delegate.initialCommands);
        assertSame(initialEvents, delegate.initialEvents);
        assertSame(event, delegate.event);
        assertEquals(1, session.partialUpdates);
        assertFalse(session.lastClear);
        assertEquals(1, delegate.closeCount);
    }

    @Test
    void deliversInitialAndRefreshSnapshotsToTheStandardRenderer() {
        RecordingDelegate delegate = new RecordingDelegate();
        RecordingSession session = new RecordingSession();
        StandardCommandUiController controller = new StandardCommandUiController(delegate);

        controller.buildInitial(new CommandUiOpenContext(), session, session.snapshot(),
                null, null, new UICommandBuilder(), new UIEventBuilder());
        CommandUiSnapshot refreshed = session.snapshot().withPresentationRevision(2L);
        controller.update(new CommandUiUpdate(refreshed, session.snapshot(),
                com.alechilles.alecstamework.api.commandui.CommandUiChangeSet.full()),
                new UICommandBuilder(), new UIEventBuilder());

        assertSame(session.snapshot(), delegate.initialSnapshot);
        assertSame(refreshed, delegate.refreshSnapshot);
    }

    private static final class RecordingDelegate
            implements StandardCommandUiController.Delegate {
        private LinkedNpcPanelPacketSender packetSender;
        private UICommandBuilder initialCommands;
        private UIEventBuilder initialEvents;
        private CommandSelectionEventData event;
        private CommandUiSnapshot initialSnapshot;
        private CommandUiSnapshot refreshSnapshot;
        private int closeCount;

        @Override
        public void configureHostPacketSender(LinkedNpcPanelPacketSender sender) {
            packetSender = sender;
        }

        @Override
        public void configureDefaultDecorations(CommandUiSnapshot snapshot) {
            initialSnapshot = snapshot;
        }

        @Override
        public void updateDefaultDecorations(CommandUiSnapshot snapshot,
                                             UICommandBuilder commands) {
            refreshSnapshot = snapshot;
        }

        @Override
        public void build(com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,
                          UICommandBuilder commands,
                          UIEventBuilder events,
                          com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store) {
            initialCommands = commands;
            initialEvents = events;
        }

        @Override
        public void handleDataEvent(com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,
                                    com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,
                                    CommandSelectionEventData event) {
            this.event = event;
        }

        @Override
        public void closeForHost() {
            closeCount++;
        }

        private void sendBackgroundUpdate() {
            packetSender.send(new UICommandBuilder(), new UIEventBuilder());
        }
    }

    private static final class RecordingSession implements CommandUiSession {
        private final CommandUiSnapshot snapshot = new CommandUiSnapshot(
                UUID.randomUUID(), 1L, 1L, null, List.of(), List.of(),
                new CommandUiPanelState("linked"));
        private int partialUpdates;
        private boolean lastClear;

        @Override public UUID sessionId() { return snapshot.sessionId(); }
        @Override public CommandUiSnapshot snapshot() { return snapshot; }
        @Override public CompletionStage<CommandUiActionResult> invoke(
                CommandUiActionHandle handle) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.unavailable("not used"));
        }
        @Override public boolean requestRefresh() { return true; }
        @Override public CommandUiUpdateSink updateSink() {
            return new CommandUiUpdateSink() {
                @Override public boolean requestRefresh() { return true; }
                @Override public boolean submit(UICommandBuilder commands,
                                                UIEventBuilder events,
                                                boolean clear) {
                    partialUpdates++;
                    lastClear = clear;
                    return true;
                }
            };
        }
        @Override public void close(CommandUiCloseReason reason) { }
    }
}

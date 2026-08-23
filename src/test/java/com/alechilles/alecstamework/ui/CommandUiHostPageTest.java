package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiCloseReason;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Consumer-visible lifecycle tests for the guarded command UI host. */
class CommandUiHostPageTest {
    private final Logger hostLogger = Logger.getLogger(
            CommandUiHostPage.class.getName());
    private Level previousLevel;

    @BeforeEach
    void suppressExpectedFailureLogs() {
        previousLevel = hostLogger.getLevel();
        hostLogger.setLevel(Level.OFF);
    }

    @AfterEach
    void restoreFailureLogs() {
        hostLogger.setLevel(previousLevel);
    }

    @Test
    void initialBuildAndUpdateReachTheControllerWithFullSnapshots() {
        TestSession session = new TestSession(snapshot(1L));
        TestController controller = new TestController();
        RecordingEmitter emitter = new RecordingEmitter();
        CommandUiHostPage<TestEvent> host = host(
                session, controller, directDispatcher(), ignored -> { }, emitter);

        UICommandBuilder initialCommands = new UICommandBuilder();
        UIEventBuilder initialEvents = new UIEventBuilder();
        host.build(null, initialCommands, initialEvents, null);
        CommandUiSnapshot next = snapshot(2L);
        session.snapshot = next;
        boolean accepted = host.applyUpdate(new CommandUiUpdate(
                next, snapshot(1L), CommandUiChangeSet.full()));

        assertSame(initialCommands, controller.initialCommands);
        assertSame(initialEvents, controller.initialEvents);
        assertSame(next, controller.updatedSnapshot);
        assertTrue(accepted);
        assertEquals(List.of(false), emitter.clearValues);
    }

    @Test
    void providerPartialSubmissionCanNeverClearTheWholePage() {
        TestSession session = new TestSession(snapshot(1L));
        RecordingEmitter emitter = new RecordingEmitter();
        CommandUiHostPage<TestEvent> host = host(
                session, new TestController(), directDispatcher(),
                ignored -> { }, emitter);
        host.build(null, new UICommandBuilder(), new UIEventBuilder(), null);

        boolean accepted = host.submitPartialUpdate(
                new UICommandBuilder(), new UIEventBuilder(), true);

        assertTrue(accepted);
        assertEquals(List.of(false), emitter.clearValues);
    }

    @Test
    void failedInitialBuildClosesProviderStateBeforeDeferredFallback() {
        TestSession session = new TestSession(snapshot(1L));
        TestController controller = new TestController();
        controller.failBuild = true;
        QueuedDispatcher dispatcher = new QueuedDispatcher();
        AtomicInteger fallbackOpenCount = new AtomicInteger();
        CommandUiHostPage<TestEvent> host = host(
                session, controller, dispatcher,
                ignored -> fallbackOpenCount.incrementAndGet(),
                new RecordingEmitter());

        host.build(null, new UICommandBuilder(), new UIEventBuilder(), null);

        assertEquals(CommandUiCloseReason.FAILURE, session.closeReason);
        assertEquals(1, controller.closeCount);
        assertEquals(0, fallbackOpenCount.get());
        dispatcher.runAll();
        assertEquals(1, fallbackOpenCount.get());
    }

    @Test
    void standardBuildFailureDoesNotOpenAnotherStandardHost() {
        TestSession session = new TestSession(snapshot(1L));
        TestController controller = new TestController();
        controller.failBuild = true;
        AtomicInteger fallbackOpenCount = new AtomicInteger();
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HostTester", "en-US", null, null);
        CommandUiHostPage<TestEvent> host = new CommandUiHostPage<>(
                playerRef, new CommandUiOpenContext(playerRef.getUuid(),
                "en-US", "tool-1", "config-1",
                (CommandUiProviderId) null, "generic"), session, controller,
                null, 0L, null, directDispatcher(),
                ignored -> fallbackOpenCount.incrementAndGet(),
                new RecordingEmitter());

        host.build(null, new UICommandBuilder(), new UIEventBuilder(), null);

        assertEquals(CommandUiCloseReason.FAILURE, session.closeReason);
        assertEquals(0, fallbackOpenCount.get());
    }

    @Test
    void eventFailureClosesOnlyThisSessionAndSuppressesLaterCallbacks() {
        TestSession session = new TestSession(snapshot(1L));
        TestController controller = new TestController();
        controller.failEvent = true;
        CommandUiHostPage<TestEvent> host = host(
                session, controller, directDispatcher(), ignored -> { },
                new RecordingEmitter());
        host.build(null, new UICommandBuilder(), new UIEventBuilder(), null);

        host.handleDataEvent(null, null, new TestEvent());
        host.handleDataEvent(null, null, new TestEvent());

        assertEquals(CommandUiCloseReason.FAILURE, session.closeReason);
        assertEquals(1, controller.eventCount);
        assertEquals(1, controller.closeCount);
        assertFalse(host.applyUpdate(CommandUiUpdate.initial(snapshot(2L))));
    }

    @Test
    void unregisterClosesOnlyHostsForTheRemovedProviderGeneration() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        var firstRegistration = registry.register(
                "example:menu", ignored -> null).registration();
        TestSession firstSession = new TestSession(snapshot(1L));
        CommandUiHostPage<TestEvent> firstHost = hostForRegistration(
                registry, firstRegistration.generation(), firstSession);

        firstRegistration.close();
        var replacementRegistration = registry.register(
                "example:menu", ignored -> null).registration();
        TestSession replacementSession = new TestSession(snapshot(1L));
        CommandUiHostPage<TestEvent> replacementHost = hostForRegistration(
                registry, replacementRegistration.generation(), replacementSession);
        firstRegistration.close();

        assertEquals(CommandUiCloseReason.PROVIDER_UNREGISTERED,
                firstSession.closeReason);
        assertFalse(firstHost.isOpen());
        assertTrue(replacementHost.isOpen());
        replacementRegistration.close();
        assertEquals(CommandUiCloseReason.PROVIDER_UNREGISTERED,
                replacementSession.closeReason);
    }

    @Test
    void hostClosesWhenProviderGenerationEndedBeforeSubscription() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        var registration = registry.register(
                "example:menu", ignored -> null).registration();
        long generation = registration.generation();
        registration.close();
        TestSession session = new TestSession(snapshot(1L));

        CommandUiHostPage<TestEvent> host = hostForRegistration(
                registry, generation, session);

        assertFalse(host.isOpen());
        assertEquals(CommandUiCloseReason.PROVIDER_UNREGISTERED,
                session.closeReason);
    }

    @Test
    void dismissalClosesTheControllerAndInvalidatesTheSession() {
        TestSession session = new TestSession(snapshot(1L));
        TestController controller = new TestController();
        CommandUiHostPage<TestEvent> host = host(
                session, controller, directDispatcher(), ignored -> { },
                new RecordingEmitter());
        host.build(null, new UICommandBuilder(), new UIEventBuilder(), null);

        host.onDismiss(null, null);

        assertEquals(CommandUiCloseReason.DISMISSED, session.closeReason);
        assertEquals(1, controller.closeCount);
        assertFalse(host.isOpen());
    }

    @Test
    void dismissalClosesOwnedRefreshSubscription() {
        TestSession session = new TestSession(snapshot(1L));
        CommandUiHostPage<TestEvent> host = host(
                session, new TestController(), directDispatcher(), ignored -> { },
                new RecordingEmitter());
        AtomicInteger closes = new AtomicInteger();
        assertTrue(host.own(() -> closes.incrementAndGet()));

        host.onDismiss(null, null);

        assertEquals(1, closes.get());
    }

    @Test
    void internalStandardControllerReceivesCurrentWorldContext() {
        TestSession session = new TestSession(snapshot(1L));
        ContextualTestController controller = new ContextualTestController();
        CommandUiHostPage<TestEvent> host = host(
                session, controller, directDispatcher(), ignored -> { },
                new RecordingEmitter());

        host.build(null, new UICommandBuilder(), new UIEventBuilder(), null);
        host.handleDataEvent(null, null, new TestEvent());

        assertEquals(1, controller.contextualBuildCount);
        assertEquals(1, controller.contextualEventCount);
        assertEquals(0, controller.detachedBuildCount);
        assertEquals(0, controller.detachedEventCount);
    }

    private static CommandUiHostPage<TestEvent> host(
            TestSession session,
            TestController controller,
            CommandUiHostPage.WorldDispatcher dispatcher,
            CommandUiHostPage.FallbackOpener fallback,
            RecordingEmitter emitter
    ) {
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HostTester", "en-US", null, null);
        return new CommandUiHostPage<>(
                playerRef,
                new CommandUiOpenContext(playerRef.getUuid(), "en-US",
                        "tool-1", "config-1",
                        CommandUiProviderId.of("example:menu"), "generic"),
                session,
                controller,
                CommandUiProviderId.of("example:menu"),
                7L,
                null,
                dispatcher,
                fallback,
                emitter);
    }

    private static CommandUiHostPage<TestEvent> hostForRegistration(
            CommandUiProviderRegistry registry,
            long providerGeneration,
            TestSession session
    ) {
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "HostTester", "en-US", null, null);
        return new CommandUiHostPage<>(
                playerRef,
                new CommandUiOpenContext(playerRef.getUuid(), "en-US",
                        "tool-1", "config-1",
                        CommandUiProviderId.of("example:menu"), "generic"),
                session,
                new TestController(),
                CommandUiProviderId.of("example:menu"),
                providerGeneration,
                registry,
                directDispatcher(),
                ignored -> { },
                new RecordingEmitter());
    }

    private static CommandUiSnapshot snapshot(long revision) {
        return new CommandUiSnapshot(
                UUID.nameUUIDFromBytes("host-session".getBytes()),
                revision, 1L, null, List.of(), List.of(),
                new CommandUiPanelState("linked"));
    }

    private static CommandUiHostPage.WorldDispatcher directDispatcher() {
        return (playerUuid, operation) -> {
            operation.run(null, null);
            return true;
        };
    }

    private static final class QueuedDispatcher
            implements CommandUiHostPage.WorldDispatcher {
        private final List<CommandUiHostPage.WorldOperation> queued =
                new ArrayList<>();

        @Override
        public boolean dispatch(UUID playerUuid,
                                CommandUiHostPage.WorldOperation operation) {
            queued.add(operation);
            return true;
        }

        private void runAll() {
            List<CommandUiHostPage.WorldOperation> pending = List.copyOf(queued);
            queued.clear();
            pending.forEach(operation -> operation.run(null, null));
        }
    }

    private static final class RecordingEmitter
            implements CommandUiHostPage.UpdateEmitter {
        private final List<Boolean> clearValues = new ArrayList<>();

        @Override
        public void send(UICommandBuilder commands, UIEventBuilder events,
                         boolean clear) {
            clearValues.add(clear);
        }
    }

    private static class TestController
            implements CommandUiPageController<TestEvent> {
        private UICommandBuilder initialCommands;
        private UIEventBuilder initialEvents;
        private CommandUiSnapshot updatedSnapshot;
        private boolean failBuild;
        private boolean failEvent;
        private int eventCount;
        private int closeCount;

        @Override
        public BuilderCodec<TestEvent> eventCodec() {
            return TestEvent.CODEC;
        }

        @Override
        public void buildInitial(CommandUiOpenContext context,
                                 CommandUiSession session,
                                 CommandUiSnapshot snapshot,
                                 UICommandBuilder commands,
                                 UIEventBuilder events) {
            if (failBuild) throw new IllegalStateException("build failed");
            initialCommands = commands;
            initialEvents = events;
        }

        @Override
        public void update(CommandUiUpdate update,
                           UICommandBuilder commands,
                           UIEventBuilder events) {
            updatedSnapshot = update.snapshot();
        }

        @Override
        public void handleEvent(TestEvent event, CommandUiSession session,
                                CommandUiSnapshot snapshot) {
            eventCount++;
            if (failEvent) throw new IllegalStateException("event failed");
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class ContextualTestController extends TestController
            implements CommandUiHostController<TestEvent> {
        private int contextualBuildCount;
        private int contextualEventCount;
        private int detachedBuildCount;
        private int detachedEventCount;

        @Override
        public void buildInitial(CommandUiOpenContext context,
                                 CommandUiSession session,
                                 CommandUiSnapshot snapshot,
                                 UICommandBuilder commands,
                                 UIEventBuilder events) {
            detachedBuildCount++;
        }

        @Override
        public void buildInitial(CommandUiOpenContext context,
                                 CommandUiSession session,
                                 CommandUiSnapshot snapshot,
                                 com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,
                                 com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,
                                 UICommandBuilder commands,
                                 UIEventBuilder events) {
            contextualBuildCount++;
        }

        @Override
        public void handleEvent(TestEvent event, CommandUiSession session,
                                CommandUiSnapshot snapshot) {
            detachedEventCount++;
        }

        @Override
        public void handleEvent(TestEvent event, CommandUiSession session,
                                CommandUiSnapshot snapshot,
                                com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,
                                com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,
                                UICommandBuilder commands,
                                UIEventBuilder events) {
            contextualEventCount++;
        }
    }

    private static final class TestEvent {
        private static final BuilderCodec<TestEvent> CODEC =
                BuilderCodec.builder(TestEvent.class, TestEvent::new).build();
    }

    private static final class TestSession implements CommandUiSession {
        private CommandUiSnapshot snapshot;
        private CommandUiCloseReason closeReason;

        private TestSession(CommandUiSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public UUID sessionId() { return snapshot.sessionId(); }
        @Override public CommandUiSnapshot snapshot() { return snapshot; }
        @Override public CompletionStage<CommandUiActionResult> invoke(
                CommandUiActionHandle handle) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.unavailable("not used"));
        }
        @Override public boolean requestRefresh() { return closeReason == null; }
        @Override public boolean isClosed() { return closeReason != null; }
        @Override public void close(CommandUiCloseReason reason) {
            if (closeReason == null) closeReason = reason;
        }
    }
}

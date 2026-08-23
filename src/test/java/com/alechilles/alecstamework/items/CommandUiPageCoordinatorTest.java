package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end construction of a provider host and its authoritative session. */
class CommandUiPageCoordinatorTest {
    @Test
    void selectedProviderBuildsAgainstTheFinalSessionSnapshot() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        RecordingController custom = new RecordingController();
        long generation = registry.register(
                "runeteria:husbandry", ignored -> custom)
                .registration().generation();
        CommandSelectionPageService actionService =
                new CommandSelectionPageService(null, null, null, null, null);
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, actionService);
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null, null);
        CommandUiSnapshot base = snapshot();
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiProviderId.of("runeteria:husbandry"), "generic");

        CommandUiPageCoordinator.Created created = coordinator.create(
                playerRef, context, base, RecordingController::new,
                List.of(), List.of(), (snapshot, handles) -> snapshot,
                directDispatcher(), ignored -> { });
        created.host().build(null, new UICommandBuilder(),
                new UIEventBuilder(), null);

        assertTrue(created.custom());
        assertEquals(generation, created.providerGeneration());
        assertSame(base, custom.initialSnapshot);
        assertSame(base, created.session().snapshot());
        created.session().close();
    }

    @Test
    void providerSessionCloseAlsoClosesHostAndController() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        RecordingController controller = new RecordingController();
        registry.register("runeteria:husbandry", ignored -> controller);
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, new CommandSelectionPageService(
                        null, null, null, null, null));
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null, null);
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiProviderId.of("runeteria:husbandry"), "generic");
        CommandUiPageCoordinator.Created created = coordinator.create(
                playerRef, context, snapshot(), RecordingController::new,
                List.of(), List.of(), (current, handles) -> current,
                directDispatcher(), ignored -> { });

        created.session().close();

        assertFalse(created.host().isOpen());
        assertEquals(1, controller.closeCount);
    }

    @Test
    void providerSessionCloseCleansHostWhenWorldDispatchIsRejected() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        RecordingController controller = new RecordingController();
        registry.register("runeteria:husbandry", ignored -> controller);
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, new CommandSelectionPageService(
                        null, null, null, null, null));
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null, null);
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiProviderId.of("runeteria:husbandry"), "generic");
        CommandUiPageCoordinator.Created created = coordinator.create(
                playerRef, context, snapshot(), RecordingController::new,
                List.of(), List.of(), (current, handles) -> current,
                (ignoredUuid, ignoredOperation) -> false, ignored -> { });

        created.session().close();

        assertFalse(created.host().isOpen());
        assertEquals(1, controller.closeCount);
    }

    private static CommandUiHostPage.WorldDispatcher directDispatcher() {
        return (playerUuid, operation) -> {
            operation.run(null, null);
            return true;
        };
    }

    private static CommandUiSnapshot snapshot() {
        return new CommandUiSnapshot(
                UUID.randomUUID(), 1L, 1L, null, List.of(), List.of(),
                new CommandUiPanelState("linked"));
    }

    private static final class RecordingController
            implements CommandUiPageController<TestEvent> {
        private CommandUiSnapshot initialSnapshot;
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
            initialSnapshot = snapshot;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class TestEvent {
        private static final BuilderCodec<TestEvent> CODEC =
                BuilderCodec.builder(TestEvent.class, TestEvent::new).build();
    }
}

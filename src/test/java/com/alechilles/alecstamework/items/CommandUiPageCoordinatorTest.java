package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRegistry;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void initialContributorDirtySignalPublishesAfterPageIsOwned() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController custom = new RecordingController();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:data");
        UUID rowId = UUID.randomUUID();
        AtomicInteger composeCount = new AtomicInteger();
        registry.registerRenderer("runeteria:ui", ignored -> custom);
        registry.registerContributor("runeteria:data", context ->
                new CommandUiSessionContributor() {
                    @Override
                    public CommandUiContribution compose(
                            CommandUiSnapshot base,
                            CommandUiContribution previous,
                            CommandUiDirtyScope scope
                    ) {
                        boolean refreshed = composeCount.incrementAndGet() > 1;
                        if (!refreshed) {
                            context.dirtySink().markRowsDirty(Set.of(rowId));
                        }
                        return new CommandUiContribution(
                                contributorId,
                                Map.of("ready", CommandUiValue.of(refreshed)),
                                Map.of(rowId, Map.of("ready",
                                        CommandUiValue.of(refreshed))));
                    }
                });
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, new CommandSelectionPageService(
                        null, null, null, null, null));
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null, null);
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiRendererId.of("runeteria:ui"), "generic");

        CommandUiPageCoordinator.Created created = coordinator.create(
                playerRef, context, snapshot(), RecordingController::new,
                List.of(new CommandUiContributorRequirement(
                        contributorId, false)),
                List.of(), List.of(), (current, handles) -> current,
                directDispatcher(), ignored -> { });

        assertNull(custom.updatedSnapshot);
        assertTrue(created.host().takePageOwnership());
        assertTrue(created.host().finishPageOpening(true));
        created.pageOpened();

        assertNotNull(custom.updatedSnapshot);
        assertTrue(custom.updatedSnapshot.contribution(contributorId)
                .rowValue(rowId, "ready").booleanValue());
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

    @Test
    void requiredInitialContributorFailureReturnsStandardController() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController custom = new RecordingController();
        RecordingController standard = new RecordingController();
        registry.registerRenderer("runeteria:ui", ignored -> custom);
        registry.registerContributor("runeteria:required", context -> {
            throw new IllegalStateException("factory");
        });
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, new CommandSelectionPageService(
                        null, null, null, null, null));
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null, null);
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiRendererId.of("runeteria:ui"), "generic");

        CommandUiPageCoordinator.Created created = coordinator.create(
                playerRef, context, snapshot(), () -> standard,
                List.of(new CommandUiContributorRequirement(
                        CommandUiContributorId.of("runeteria:required"), true)),
                List.of(), List.of(), (current, handles) -> current,
                directDispatcher(), ignored -> { });

        created.host().build(null, new UICommandBuilder(),
                new UIEventBuilder(), null);
        assertFalse(created.custom());
        assertEquals(1, custom.closeCount);
        assertSame(standard.initialSnapshot, created.session().snapshot());
        created.session().close();
    }

    @Test
    void finalizerFailureClosesCompositionAndRendererBeforeHostOwnership() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController renderer = new RecordingController();
        RecordingContributor contributor = new RecordingContributor();
        registry.registerRenderer("runeteria:ui", ignored -> renderer);
        registry.registerContributor("runeteria:data", ignored -> contributor);
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, new CommandSelectionPageService(
                        null, null, null, null, null));
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null, null);
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiRendererId.of("runeteria:ui"), "generic");

        assertThrows(IllegalStateException.class, () -> coordinator.create(
                playerRef, context, snapshot(), RecordingController::new,
                List.of(new CommandUiContributorRequirement(
                        CommandUiContributorId.of("runeteria:data"), true)),
                List.of(), List.of(), (current, handles) -> {
                    throw new IllegalStateException("finalizer");
                }, directDispatcher(), ignored -> { }));

        assertEquals(1, contributor.closeCount);
        assertEquals(1, renderer.closeCount);
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
        private CommandUiSnapshot updatedSnapshot;
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
        public void update(
                CommandUiUpdate update,
                UICommandBuilder commands,
                UIEventBuilder events
        ) {
            updatedSnapshot = update.snapshot();
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class RecordingContributor
            implements CommandUiSessionContributor {
        private int closeCount;

        @Override
        public com.alechilles.alecstamework.api.commandui.CommandUiContribution compose(
                CommandUiSnapshot base,
                com.alechilles.alecstamework.api.commandui.CommandUiContribution previous,
                CommandUiDirtyScope scope
        ) {
            return new com.alechilles.alecstamework.api.commandui.CommandUiContribution(
                    CommandUiContributorId.of("runeteria:data"));
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

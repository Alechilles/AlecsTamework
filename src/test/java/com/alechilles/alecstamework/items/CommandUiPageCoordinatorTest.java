package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiActionStatus;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiSession;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end construction of a renderer host and its authoritative session. */
class CommandUiPageCoordinatorTest {
    @Test
    void requiredContributorUnregisterClosesCustomPageWithOneFallback() {
        CommandUiRegistry registry = new CommandUiRegistry();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:required");
        registry.registerRenderer("runeteria:ui",
                ignored -> new RecordingController());
        var registration = registry.registerContributor(
                contributorId.value(), ignored ->
                        (base, previous, scope) ->
                                new CommandUiContribution(contributorId))
                .registration();
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, new CommandSelectionPageService(
                        null, null, null, null, null));
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null,
                null);
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiRendererId.of("runeteria:ui"), "generic");
        AtomicInteger fallbacks = new AtomicInteger();

        CommandUiPageCoordinator.Created created = coordinator.create(
                playerRef, context, snapshot(), RecordingController::new,
                List.of(new CommandUiContributorRequirement(
                        contributorId, true)), List.of(), List.of(),
                (current, handles) -> current, directDispatcher(),
                ignored -> fallbacks.incrementAndGet());
        created.host().build(null, new UICommandBuilder(),
                new UIEventBuilder(), null);
        assertTrue(created.host().takePageOwnership());
        assertTrue(created.host().finishPageOpening(true));
        created.pageOpened();

        registration.close();

        assertFalse(created.host().isOpen());
        assertFalse(created.session().isOpen());
        assertEquals(1, fallbacks.get());
        registration.close();
        assertEquals(1, fallbacks.get());
    }

    @Test
    void productionCompositionPublishesAndInvokesContributorActionHandle() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController custom = new RecordingController();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        AtomicInteger calls = new AtomicInteger();
        CommandUiContributorAction action = new CommandUiContributorAction(
                "ping", "PING", "Ping",
                CommandUiContributorAction.InputPolicy.NONE, false,
                context -> {
                    calls.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            CommandUiActionResult.applied());
                });
        registry.registerRenderer("runeteria:ui", ignored -> custom);
        registry.registerContributor("runeteria:actions", context ->
                new CommandUiSessionContributor() {
                    @Override
                    public CommandUiContribution compose(
                            CommandUiSnapshot base,
                            CommandUiContribution previous,
                            CommandUiDirtyScope scope
                    ) {
                        return CommandUiContribution.withActions(
                                contributorId, Map.of(), Map.of(), Map.of(),
                                Map.of("ping", action), Map.of(), Map.of());
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
                        contributorId, false)), List.of(), List.of(),
                (current, handles) -> current, directDispatcher(), ignored -> { });
        created.host().build(null, new UICommandBuilder(),
                new UIEventBuilder(), null);

        CommandUiActionHandle handle = custom.initialSnapshot
                .contribution(contributorId).commandActions()
                .get("runeteria:actions/ping").handle();
        assertNotNull(handle);
        assertEquals(CommandUiActionStatus.APPLIED,
                created.session().invoke(handle).toCompletableFuture().join().status());
        assertEquals(1, calls.get());
        created.session().close();
    }

    @Test
    void contributorPresentationRefreshRetainsOpaqueHandle() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController custom = new RecordingController();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        AtomicInteger revisions = new AtomicInteger();
        AtomicReference<com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink>
                sink = new AtomicReference<>();
        CommandUiContributorAction action = new CommandUiContributorAction(
                "ping", "PING", "Ping",
                CommandUiContributorAction.InputPolicy.NONE, false,
                context -> java.util.concurrent.CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()));
        registry.registerRenderer("runeteria:ui", ignored -> custom);
        registry.registerContributor("runeteria:actions", context -> {
            sink.set(context.dirtySink());
            return new CommandUiSessionContributor() {
                @Override
                public CommandUiContribution compose(
                        CommandUiSnapshot base,
                        CommandUiContribution previous,
                        CommandUiDirtyScope scope
                ) {
                    return CommandUiContribution.withActions(
                            contributorId,
                            Map.of("revision", CommandUiValue.of(
                                    revisions.incrementAndGet())),
                            Map.of(), Map.of(), Map.of("ping", action),
                            Map.of(), Map.of());
                }
            };
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
                        contributorId, false)), List.of(), List.of(),
                (current, handles) -> current, directDispatcher(), ignored -> { });
        created.host().build(null, new UICommandBuilder(),
                new UIEventBuilder(), null);
        assertTrue(created.host().takePageOwnership());
        assertTrue(created.host().finishPageOpening(true));
        created.pageOpened();

        String initialToken = custom.initialSnapshot.contribution(contributorId)
                .commandActions().get("runeteria:actions/ping").handle().token();
        sink.get().markPageDirty();
        assertEquals(initialToken, custom.updatedSnapshot
                .contribution(contributorId).commandActions()
                .get("runeteria:actions/ping").handle().token());
        assertEquals(1L, created.session().snapshot().actionGeneration());
        created.session().close();
    }

    @Test
    void stableContributorHandleAndConfirmationUseTheLatestComposedHandler() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController custom = new RecordingController();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        AtomicInteger revisions = new AtomicInteger();
        AtomicInteger executedRevision = new AtomicInteger();
        AtomicReference<com.alechilles.alecstamework.api.commandui.CommandUiContributorDirtySink>
                sink = new AtomicReference<>();
        registry.registerRenderer("runeteria:ui", ignored -> custom);
        registry.registerContributor("runeteria:actions", context -> {
            sink.set(context.dirtySink());
            return (base, previous, scope) -> {
                int revision = revisions.incrementAndGet();
                CommandUiContributorAction action =
                        new CommandUiContributorAction(
                                "ping", "PING", "Ping",
                                CommandUiContributorAction.InputPolicy.NONE,
                                true, actionContext -> {
                            executedRevision.set(revision);
                            return java.util.concurrent.CompletableFuture
                                    .completedFuture(
                                            CommandUiActionResult.applied());
                        });
                return CommandUiContribution.withActions(contributorId,
                        Map.of("revision", CommandUiValue.of(revision)),
                        Map.of(), Map.of(), Map.of("ping", action),
                        Map.of(), Map.of());
            };
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
                        contributorId, false)), List.of(), List.of(),
                (current, handles) -> current, directDispatcher(), ignored -> { });
        created.host().build(null, new UICommandBuilder(),
                new UIEventBuilder(), null);
        assertTrue(created.host().takePageOwnership());
        assertTrue(created.host().finishPageOpening(true));
        created.pageOpened();
        String token = created.session().snapshot().contribution(contributorId)
                .commandActions().get("runeteria:actions/ping").handle().token();
        CommandUiActionResult confirmation = created.session().invoke(
                        new CommandUiActionHandle(token))
                .toCompletableFuture().join();
        assertEquals(CommandUiActionStatus.CONFIRMATION_REQUIRED,
                confirmation.status());

        sink.get().markPageDirty();

        assertEquals(token, created.session().snapshot()
                .contribution(contributorId).commandActions()
                .get("runeteria:actions/ping").handle().token());
        assertEquals(CommandUiActionStatus.APPLIED,
                created.session().invoke(confirmation.confirmationHandle())
                        .toCompletableFuture().join().status());
        assertEquals(2, executedRevision.get());
        created.session().close();
    }

    @Test
    void contributorActionResultRequestsTheExistingSessionRefresh() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController custom = new RecordingController();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        AtomicInteger refreshes = new AtomicInteger();
        CommandUiContributorAction action = new CommandUiContributorAction(
                "ping", "PING", "Ping",
                CommandUiContributorAction.InputPolicy.NONE, false,
                context -> java.util.concurrent.CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()));
        registry.registerRenderer("runeteria:ui", ignored -> custom);
        registry.registerContributor("runeteria:actions", context ->
                (base, previous, scope) -> CommandUiContribution.withActions(
                        contributorId, Map.of(), Map.of(), Map.of(),
                        Map.of("ping", action), Map.of(), Map.of()));
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
                        contributorId, false)), List.of(), List.of(),
                (current, handles) -> current, refreshes::incrementAndGet,
                directDispatcher(), ignored -> { });
        CommandUiActionHandle handle = created.session().snapshot()
                .contribution(contributorId).commandActions()
                .get("runeteria:actions/ping").handle();

        assertEquals(CommandUiActionStatus.APPLIED,
                created.session().invoke(handle).toCompletableFuture().join().status());
        assertEquals(1, refreshes.get());
        assertTrue(created.session().consumeActionRebindRequired());
        created.session().close();
    }

    @Test
    void baseActionGenerationChangeReissuesContributorHandles() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController custom = new RecordingController();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "runeteria:actions");
        CommandUiContributorAction action = new CommandUiContributorAction(
                "ping", "PING", "Ping",
                CommandUiContributorAction.InputPolicy.NONE, false,
                context -> java.util.concurrent.CompletableFuture.completedFuture(
                        CommandUiActionResult.applied()));
        registry.registerRenderer("runeteria:ui", ignored -> custom);
        registry.registerContributor("runeteria:actions", context ->
                (base, previous, scope) -> CommandUiContribution.withActions(
                        contributorId, Map.of(), Map.of(), Map.of(),
                        Map.of("ping", action), Map.of(), Map.of()));
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
                        contributorId, false)), List.of(), List.of(),
                (current, handles) -> current, directDispatcher(), ignored -> { });
        CommandUiSnapshot previous = created.session().snapshot();
        String oldToken = previous.contribution(contributorId).commandActions()
                .get("runeteria:actions/ping").handle().token();

        CommandUiSnapshot rebound = created.reconcileContributorActions(
                previous.withActionGeneration(previous.actionGeneration() + 1L),
                previous);

        String newToken = rebound.contribution(contributorId).commandActions()
                .get("runeteria:actions/ping").handle().token();
        assertFalse(oldToken.equals(newToken));
        assertEquals(previous.actionGeneration() + 1L,
                rebound.actionGeneration());
        created.session().close();
    }

    @Test
    void selectedRendererBuildsAgainstTheFinalSessionSnapshot() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController custom = new RecordingController();
        long generation = registry.registerRenderer(
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
                CommandUiRendererId.of("runeteria:husbandry"), "generic");

        CommandUiPageCoordinator.Created created = coordinator.create(
                playerRef, context, base, RecordingController::new,
                List.of(), List.of(), (snapshot, handles) -> snapshot,
                directDispatcher(), ignored -> { });
        created.host().build(null, new UICommandBuilder(),
                new UIEventBuilder(), null);

        assertTrue(created.custom());
        assertEquals(generation, created.rendererGeneration());
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
    void rendererSessionCloseAlsoClosesHostAndController() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController controller = new RecordingController();
        registry.registerRenderer("runeteria:husbandry", ignored -> controller);
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, new CommandSelectionPageService(
                        null, null, null, null, null));
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null, null);
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiRendererId.of("runeteria:husbandry"), "generic");
        CommandUiPageCoordinator.Created created = coordinator.create(
                playerRef, context, snapshot(), RecordingController::new,
                List.of(), List.of(), (current, handles) -> current,
                directDispatcher(), ignored -> { });

        created.session().close();

        assertFalse(created.host().isOpen());
        assertEquals(1, controller.closeCount);
    }

    @Test
    void rendererSessionCloseCleansHostWhenWorldDispatchIsRejected() {
        CommandUiRegistry registry = new CommandUiRegistry();
        RecordingController controller = new RecordingController();
        registry.registerRenderer("runeteria:husbandry", ignored -> controller);
        CommandUiPageCoordinator coordinator = new CommandUiPageCoordinator(
                registry, new CommandSelectionPageService(
                        null, null, null, null, null));
        PlayerRef playerRef = new PlayerRef(
                null, UUID.randomUUID(), "CoordinatorTester", "en-US", null, null);
        CommandUiOpenContext context = new CommandUiOpenContext(
                playerRef.getUuid(), "en-US", "tool-1", "config-1",
                CommandUiRendererId.of("runeteria:husbandry"), "generic");
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

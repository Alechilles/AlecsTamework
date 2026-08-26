package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributionStatus;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDirtySink;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.internal.CommandHudContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandHudRendererRegistry;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session-scoped contributor composition and bounded invalidation behavior. */
class CommandHudCompositionSessionTest {
    @Test
    void markPathsDirtyRecomposesOnlyContributorAndEmitsItsPathHint() {
        CommandHudContributorId id = CommandHudContributorId.of("example:badge");
        AtomicReference<CommandHudContributorDirtySink> sink = new AtomicReference<>();
        AtomicInteger composeCalls = new AtomicInteger();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        contributors.registerTarget(id.value(), context -> {
            sink.set(context.dirtySink());
            return targetContributor(id, composeCalls);
        });
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", ignored -> targetController());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer",
                List.of(new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                        id, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudView,
                CommandTargetHudUpdate> session = resolver.openTarget(
                new CommandHudOpenContext(), resolution);

        session.compose(baseSnapshot());
        sink.get().markPathsDirty(Set.of("badge"));
        CommandTargetHudUpdate update = session.refresh(baseSnapshot());

        assertNotNull(update);
        assertEquals(2, composeCalls.get());
        assertEquals(Set.of("badge"), update.changeSet().pathsFor(id));
        assertFalse(update.changeSet().fullRefresh());
        assertTrue(update.changeSet().changedSections().contains(
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet.Section.CONTRIBUTIONS));
        session.close();
    }

    @Test
    void baseSnapshotChangeKeepsDirtyPathFocusedOnOneContributor() {
        CommandHudContributorId indicatorId = CommandHudContributorId.of("example:indicator");
        CommandHudContributorId badgeId = CommandHudContributorId.of("example:badge");
        AtomicReference<CommandHudContributorDirtySink> indicatorSink = new AtomicReference<>();
        AtomicInteger indicatorCalls = new AtomicInteger();
        AtomicInteger badgeCalls = new AtomicInteger();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        contributors.registerTarget(indicatorId.value(), context -> {
            indicatorSink.set(context.dirtySink());
            return targetContributor(indicatorId, indicatorCalls);
        });
        contributors.registerTarget(badgeId.value(), context -> targetContributor(badgeId, badgeCalls));
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", ignored -> targetController());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of(
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                indicatorId, false),
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                badgeId, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session = resolver.openTarget(
                new CommandHudOpenContext(), resolution);

        session.compose(baseSnapshot());
        indicatorSink.get().markPathsDirty(Set.of("indicator"));
        CommandTargetHudUpdate update = session.updateBase(baseSnapshot("changed"));

        assertNotNull(update);
        assertEquals(2, indicatorCalls.get());
        assertEquals(1, badgeCalls.get());
        assertEquals(Set.of("indicator"), update.changeSet().pathsFor(indicatorId));
        assertTrue(update.changeSet().pathsFor(badgeId).isEmpty());
        assertFalse(update.changeSet().contributorFullRefresh(indicatorId));
        session.close();
    }

    @Test
    void overflowDirtyPathsPromoteToFullContributorRefresh() {
        CommandHudContributorId id = CommandHudContributorId.of("example:overflow");
        AtomicReference<CommandHudContributorDirtySink> sink = new AtomicReference<>();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        contributors.registerTarget(id.value(), context -> {
            sink.set(context.dirtySink());
            return targetContributor(id, new AtomicInteger());
        });
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", ignored -> targetController());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer",
                List.of(new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                        id, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudView,
                CommandTargetHudUpdate> session = resolver.openTarget(
                new CommandHudOpenContext(), resolution);
        session.compose(baseSnapshot());

        Set<String> paths = new HashSet<>();
        for (int index = 0; index < 257; index++) paths.add("path." + index);
        sink.get().markPathsDirty(paths);
        CommandTargetHudUpdate update = session.refresh(baseSnapshot());

        assertNotNull(update);
        assertTrue(update.changeSet().contributorFullRefresh(id));
        assertTrue(update.changeSet().pathsFor(id).isEmpty());
        session.close();
    }

    @Test
    void optionalContributorFailureIsIsolatedAndRequiredFailureClosesSession() {
        CommandHudContributorId optionalId = CommandHudContributorId.of("example:optional");
        AtomicReference<CommandHudContributorDirtySink> optionalSink = new AtomicReference<>();
        AtomicInteger optionalCalls = new AtomicInteger();
        CommandHudContributorRegistry optionalRegistry = new CommandHudContributorRegistry();
        optionalRegistry.registerTarget(optionalId.value(), context -> {
            optionalSink.set(context.dirtySink());
            return (base, previous, scope) -> {
                if (optionalCalls.incrementAndGet() > 1) {
                    throw new IllegalStateException("private contributor failure");
                }
                return new CommandHudContribution(optionalId,
                        Map.of("state", CommandUiValue.of("ready")));
            };
        });
        CommandHudRendererRegistry optionalRenderers = new CommandHudRendererRegistry();
        optionalRenderers.registerTarget("example:renderer", ignored -> targetController());
        CommandHudCompositionResolver optionalResolver = new CommandHudCompositionResolver(
                optionalRenderers, optionalRegistry);
        CommandHudTargetResolution optionalResolution = optionalResolver.resolveTarget(
                "example:renderer",
                List.of(new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                        optionalId, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudView,
                CommandTargetHudUpdate> optionalSession = optionalResolver.openTarget(
                new CommandHudOpenContext(), optionalResolution);
        optionalSession.compose(baseSnapshot());
        optionalSink.get().markAllDirty();
        optionalSession.refresh(baseSnapshot());

        assertTrue(optionalSession.isOpen());
        assertEquals(CommandHudContributionStatus.FAILED,
                optionalSession.view().contribution(optionalId).status());
        optionalSession.close();

        CommandHudContributorId requiredId = CommandHudContributorId.of("example:required");
        AtomicReference<CommandHudContributorDirtySink> requiredSink = new AtomicReference<>();
        CommandHudContributorRegistry requiredRegistry = new CommandHudContributorRegistry();
        requiredRegistry.registerTarget(requiredId.value(), context -> {
            requiredSink.set(context.dirtySink());
            return (base, previous, scope) -> {
                if (previous != null) throw new IllegalStateException("required failure");
                return new CommandHudContribution(requiredId, Map.of());
            };
        });
        CommandHudRendererRegistry requiredRenderers = new CommandHudRendererRegistry();
        requiredRenderers.registerTarget("example:renderer", ignored -> targetController());
        CommandHudCompositionResolver requiredResolver = new CommandHudCompositionResolver(
                requiredRenderers, requiredRegistry);
        CommandHudTargetResolution requiredResolution = requiredResolver.resolveTarget(
                "example:renderer",
                List.of(new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                        requiredId, true)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudView,
                CommandTargetHudUpdate> requiredSession = requiredResolver.openTarget(
                new CommandHudOpenContext(), requiredResolution);
        requiredSession.compose(baseSnapshot());
        requiredSink.get().markAllDirty();
        requiredSession.refresh(baseSnapshot());

        assertFalse(requiredSession.isOpen());
        requiredSession.close();
    }

    @Test
    void targetRendererUnregisterClosesAnEmptyContributorSession() {
        AtomicInteger rendererCloses = new AtomicInteger();
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        CommandHudRegistration registration = renderers.registerTarget(
                "example:renderer", ignored -> closeCountingTargetController(rendererCloses))
                .registration();
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, new CommandHudContributorRegistry());
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of());
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session = resolver.openTarget(
                new CommandHudOpenContext(), resolution);
        session.compose(baseSnapshot());

        registration.close();

        assertFalse(session.isOpen());
        assertEquals(1, rendererCloses.get());
        assertEquals(0, resolver.diagnostics().snapshot().activeSessionCount());
        session.close();
    }

    @Test
    void hotswapRendererUnregisterClosesAnEmptyContributorSession() {
        AtomicInteger rendererCloses = new AtomicInteger();
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        CommandHudRegistration registration = renderers.registerHotswap(
                "example:renderer", ignored -> closeCountingHotswapController(rendererCloses))
                .registration();
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, new CommandHudContributorRegistry());
        CommandHudHotswapResolution resolution = resolver.resolveHotswap(
                "example:renderer", List.of());
        CommandHudCompositionSession<CommandHotswapHudSnapshot,
                com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView,
                CommandHotswapHudUpdate> session = resolver.openHotswap(
                new CommandHudOpenContext(), resolution);
        session.compose(baseHotswapSnapshot());

        registration.close();

        assertFalse(session.isOpen());
        assertEquals(1, rendererCloses.get());
        assertEquals(0, resolver.diagnostics().snapshot().activeSessionCount());
        session.close();
    }

    @Test
    void optionalContributorUnregisterDuringInitialComposeKeepsCustomSession() {
        CommandHudContributorId id = CommandHudContributorId.of("example:optional");
        AtomicReference<CommandHudRegistration> registration = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        registration.set(contributors.registerTarget(id.value(), ignored -> (base, previous, scope) -> {
            if (calls.incrementAndGet() == 1) registration.get().close();
            return new CommandHudContribution(id, Map.of(
                    "state", CommandUiValue.of("stale")));
        }).registration());
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", ignored -> targetController());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of(
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                id, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session = resolver.openTarget(
                new CommandHudOpenContext(), resolution);

        CommandTargetHudView view = session.compose(baseSnapshot());

        assertTrue(session.custom());
        assertTrue(session.isOpen());
        assertEquals(CommandHudContributionStatus.UNAVAILABLE,
                view.contribution(id).status());
        assertEquals(1, calls.get());
        session.close();
    }

    @Test
    void requiredContributorRemovalClosesAllContributorsInReverseOrderThenRenderer() {
        CommandHudContributorId requiredId = CommandHudContributorId.of("example:required");
        CommandHudContributorId optionalId = CommandHudContributorId.of("example:optional");
        List<String> closed = new java.util.ArrayList<>();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        CommandHudRegistration requiredRegistration = contributors.registerTarget(
                requiredId.value(), ignored -> closeCountingTargetContributor(
                        requiredId, "required", closed)).registration();
        contributors.registerTarget(optionalId.value(), ignored -> closeCountingTargetContributor(
                optionalId, "optional", closed));
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", ignored -> new CommandTargetHudController() {
            @Override
            public void buildInitial(
                    CommandHudOpenContext context,
                    CommandTargetHudView view,
                    com.hypixel.hytale.server.core.ui.builder.UICommandBuilder commands
            ) {
            }

            @Override
            public void close() {
                closed.add("renderer");
            }
        });
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of(
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                requiredId, true),
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                optionalId, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session = resolver.openTarget(
                new CommandHudOpenContext(), resolution);
        session.compose(baseSnapshot());

        requiredRegistration.close();

        assertFalse(session.isOpen());
        assertEquals(List.of("optional", "required", "renderer"), closed);
        session.close();
    }

    @Test
    void staleTargetUpdateIsDroppedWhenRendererUnregistersDuringCallback() {
        CommandHudContributorId id = CommandHudContributorId.of("example:race");
        AtomicReference<CommandHudContributorDirtySink> sink = new AtomicReference<>();
        AtomicReference<CommandHudRegistration> rendererRegistration = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        contributors.registerTarget(id.value(), context -> {
            sink.set(context.dirtySink());
            return (base, previous, scope) -> {
                if (calls.incrementAndGet() == 2) rendererRegistration.get().close();
                return new CommandHudContribution(id, Map.of("state", CommandUiValue.of("ok")));
            };
        });
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        rendererRegistration.set(renderers.registerTarget("example:renderer", ignored -> targetController())
                .registration());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of(
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                id, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session =
                CommandHudCompositionSession.target(new CommandHudOpenContext(), resolution,
                        resolver.diagnostics(), resolver.timingWarnings,
                        ignored -> published.incrementAndGet(), null, null);
        session.compose(baseSnapshot());

        sink.get().markAllDirty();
        assertNull(session.refresh(baseSnapshot()));
        assertEquals(0, published.get());
        assertFalse(session.isOpen());
        session.close();
    }

    @Test
    void blockingTargetControllerDropsUpdateWhenExactRendererUnregisters() throws Exception {
        BlockingTargetController controller = new BlockingTargetController();
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        CommandHudRegistration registration = renderers.registerTarget(
                "example:renderer", ignored -> controller).registration();
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, new CommandHudContributorRegistry());
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of());
        AtomicReference<CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate>> sessionReference =
                new AtomicReference<>();
        AtomicInteger publishedCommands = new AtomicInteger();
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session =
                CommandHudCompositionSession.target(new CommandHudOpenContext(), resolution,
                        resolver.diagnostics(), resolver.timingWarnings,
                        update -> {
                            controller.update(update,
                                    new com.hypixel.hytale.server.core.ui.builder.UICommandBuilder());
                            if (sessionReference.get().runIfCurrent(
                                    publishedCommands::incrementAndGet)) {
                                // The lifecycle gate accepted the client command batch.
                            }
                        }, null, null);
        sessionReference.set(session);
        session.compose(baseSnapshot());
        CountDownLatch unregisterObserved = new CountDownLatch(1);
        AutoCloseable observation = renderers.subscribeTargetUnregister(
                (id, generation) -> unregisterObserved.countDown());
        Thread updateThread = new Thread(
                () -> session.updateBase(baseSnapshot("changed")), "target-hud-update");
        Thread unregisterThread = new Thread(registration::close, "target-hud-unregister");
        try {
            updateThread.start();
            assertTrue(controller.updateStarted.await(5, TimeUnit.SECONDS));
            unregisterThread.start();
            assertTrue(unregisterObserved.await(5, TimeUnit.SECONDS));
            controller.releaseUpdate.countDown();
            updateThread.join(5_000L);
            unregisterThread.join(5_000L);

            assertFalse(updateThread.isAlive());
            assertFalse(unregisterThread.isAlive());
            assertEquals(0, publishedCommands.get());
            assertEquals(1, controller.closed.get());
            assertFalse(session.isOpen());
        } finally {
            controller.releaseUpdate.countDown();
            if (updateThread.isAlive()) updateThread.join(5_000L);
            if (unregisterThread.isAlive()) unregisterThread.join(5_000L);
            observation.close();
            session.close();
        }
    }

    @Test
    void optionalContributorUnregisterPublishesUnavailableOnlyOnce() {
        CommandHudContributorId id = CommandHudContributorId.of("example:optional");
        AtomicReference<CommandHudContributorDirtySink> sink = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        CommandHudRegistration contributorRegistration = contributors.registerTarget(id.value(), context -> {
            sink.set(context.dirtySink());
            return targetContributor(id, calls);
        }).registration();
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", ignored -> targetController());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of(
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                id, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session = resolver.openTarget(
                new CommandHudOpenContext(), resolution);
        session.compose(baseSnapshot());

        contributorRegistration.close();

        CommandTargetHudUpdate first = session.refresh(baseSnapshot());
        CommandTargetHudUpdate second = session.refresh(baseSnapshot());

        assertNotNull(first);
        assertEquals(CommandHudContributionStatus.UNAVAILABLE,
                first.view().contribution(id).status());
        assertNull(second);
        assertEquals(1, calls.get());
        session.close();
    }

    @Test
    void initialRequiredFailureIsNotRetriedAfterClose() {
        CommandHudContributorId id = CommandHudContributorId.of("example:required");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        contributors.registerTarget(id.value(), ignored -> (base, previous, scope) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("private failure");
        });
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", ignored -> targetController());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of(
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                id, true)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session =
                CommandHudCompositionSession.target(new CommandHudOpenContext(), resolution,
                        resolver.diagnostics(), resolver.timingWarnings, null, null,
                        (ignoredId, reason) -> failures.incrementAndGet());

        assertThrows(CommandHudCompositionSession.InitialCompositionFailure.class,
                () -> session.compose(baseSnapshot()));
        assertThrows(CommandHudCompositionSession.InitialCompositionFailure.class,
                () -> session.compose(baseSnapshot()));

        assertEquals(1, calls.get());
        assertEquals(1, failures.get());
        session.close();
    }

    @Test
    void hotswapSessionRecomposesContributorWithBoundedScope() {
        CommandHudContributorId id = CommandHudContributorId.of("example:badge");
        AtomicReference<CommandHudContributorDirtySink> sink = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        contributors.registerHotswap(id.value(), context -> {
            sink.set(context.dirtySink());
            return hotswapContributor(id, calls);
        });
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerHotswap("example:renderer", ignored -> hotswapController());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudHotswapResolution resolution = resolver.resolveHotswap(
                "example:renderer", List.of(
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                id, false)));
        CommandHudCompositionSession<CommandHotswapHudSnapshot,
                com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView,
                CommandHotswapHudUpdate> session = resolver.openHotswap(
                new CommandHudOpenContext(), resolution);
        session.compose(baseHotswapSnapshot());

        sink.get().markPathsDirty(Set.of("badge"));
        CommandHotswapHudUpdate update = session.refresh(baseHotswapSnapshot());

        assertNotNull(update);
        assertEquals(Set.of("badge"), update.changeSet().pathsFor(id));
        assertEquals(2, calls.get());
        session.close();
    }

    @Test
    void closeReleasesContributorBeforeRendererAndIsIdempotent() {
        CommandHudContributorId id = CommandHudContributorId.of("example:order");
        List<String> closed = new java.util.ArrayList<>();
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        contributors.registerTarget(id.value(), ignored -> new CommandTargetHudSessionContributor() {
            @Override
            public CommandHudContribution compose(
                    CommandTargetHudSnapshot base,
                    CommandHudContribution previous,
                    com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope scope
            ) {
                return new CommandHudContribution(id, Map.of());
            }

            @Override
            public void close() {
                closed.add("contributor");
            }
        });
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", ignored -> new com.alechilles.alecstamework.api.commandhud.CommandTargetHudController() {
            @Override
            public void buildInitial(
                    CommandHudOpenContext context,
                    CommandTargetHudView view,
                    com.hypixel.hytale.server.core.ui.builder.UICommandBuilder commands
            ) {
            }

            @Override
            public void close() {
                closed.add("renderer");
            }
        });
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);
        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer", List.of(
                        new com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement(
                                id, false)));
        CommandHudCompositionSession<CommandTargetHudSnapshot,
                CommandTargetHudView, CommandTargetHudUpdate> session = resolver.openTarget(
                new CommandHudOpenContext(), resolution);
        session.compose(baseSnapshot());

        session.close();
        session.close();

        assertEquals(List.of("contributor", "renderer"), closed);
    }

    private static CommandTargetHudSnapshot baseSnapshot() {
        return baseSnapshot("ready");
    }

    private static CommandTargetHudSnapshot baseSnapshot(String lifecycleStatus) {
        return new CommandTargetHudSnapshot(null, "target", null, lifecycleStatus,
                null, null, null, List.of(), List.of(), null, null, List.of(), null);
    }

    private static CommandHotswapHudSnapshot baseHotswapSnapshot() {
        return new CommandHotswapHudSnapshot(null, null, null, null, null, null);
    }

    private static CommandTargetHudSessionContributor targetContributor(
            CommandHudContributorId id,
            AtomicInteger calls
    ) {
        return (base, previous, scope) -> new CommandHudContribution(id,
                Map.of("state", CommandUiValue.of(calls.incrementAndGet())));
    }

    private static CommandTargetHudSessionContributor closeCountingTargetContributor(
            CommandHudContributorId id,
            String label,
            List<String> closed
    ) {
        return new CommandTargetHudSessionContributor() {
            @Override
            public CommandHudContribution compose(
                    CommandTargetHudSnapshot base,
                    CommandHudContribution previous,
                    com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope scope
            ) {
                return new CommandHudContribution(id, Map.of());
            }

            @Override
            public void close() {
                closed.add(label);
            }
        };
    }

    private static com.alechilles.alecstamework.api.commandhud.CommandTargetHudController
    targetController() {
        return new com.alechilles.alecstamework.api.commandhud.CommandTargetHudController() {
            @Override
            public void buildInitial(
                    CommandHudOpenContext context,
                    com.alechilles.alecstamework.api.commandhud.CommandTargetHudView view,
                    com.hypixel.hytale.server.core.ui.builder.UICommandBuilder commands
            ) {
            }
        };
    }

    private static CommandHotswapHudController hotswapController() {
        return new CommandHotswapHudController() {
            @Override
            public void buildInitial(
                    CommandHudOpenContext context,
                    com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView view,
                    com.hypixel.hytale.server.core.ui.builder.UICommandBuilder commands
            ) {
            }
        };
    }

    private static com.alechilles.alecstamework.api.commandhud.CommandTargetHudController
    closeCountingTargetController(AtomicInteger closes) {
        return new com.alechilles.alecstamework.api.commandhud.CommandTargetHudController() {
            @Override
            public void buildInitial(
                    CommandHudOpenContext context,
                    CommandTargetHudView view,
                    com.hypixel.hytale.server.core.ui.builder.UICommandBuilder commands
            ) {
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    private static CommandHotswapHudController closeCountingHotswapController(
            AtomicInteger closes
    ) {
        return new CommandHotswapHudController() {
            @Override
            public void buildInitial(
                    CommandHudOpenContext context,
                    com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView view,
                    com.hypixel.hytale.server.core.ui.builder.UICommandBuilder commands
            ) {
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    private static CommandHotswapHudSessionContributor hotswapContributor(
            CommandHudContributorId id,
            AtomicInteger calls
    ) {
        return (base, previous, scope) -> new CommandHudContribution(id,
                Map.of("state", CommandUiValue.of(calls.incrementAndGet())));
    }

    private static final class BlockingTargetController implements CommandTargetHudController {
        private final CountDownLatch updateStarted = new CountDownLatch(1);
        private final CountDownLatch releaseUpdate = new CountDownLatch(1);
        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public void buildInitial(
                CommandHudOpenContext context,
                CommandTargetHudView view,
                com.hypixel.hytale.server.core.ui.builder.UICommandBuilder commands
        ) {
        }

        @Override
        public void update(
                CommandTargetHudUpdate update,
                com.hypixel.hytale.server.core.ui.builder.UICommandBuilder commands
        ) {
            updateStarted.countDown();
            try {
                releaseUpdate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}

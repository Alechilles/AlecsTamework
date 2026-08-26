package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributionStatus;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDirtySink;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistration;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
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
        return new CommandTargetHudSnapshot(null, "target", null, "ready",
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
}

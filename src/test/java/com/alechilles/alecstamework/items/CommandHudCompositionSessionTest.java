package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributionStatus;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDirtySink;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
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

    private static CommandTargetHudSnapshot baseSnapshot() {
        return new CommandTargetHudSnapshot(null, "target", null, "ready",
                null, null, null, List.of(), List.of(), null, null, List.of(), null);
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
}

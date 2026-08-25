package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorAction;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorDescriptor;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererDescriptor;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiDirtyScope;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRendererRegistry;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Standard fallback and ordered composition resolution behavior. */
class CommandUiCompositionResolverTest {
    @Test
    void supportedDescriptorsAllowCompositionAndCreateTheRenderer() {
        CommandUiRendererRegistry renderers = new CommandUiRendererRegistry();
        CommandUiContributorRegistry contributors = new CommandUiContributorRegistry();
        AtomicInteger rendererCreates = new AtomicInteger();
        renderers.register(
                "runeteria:ui",
                new CommandUiRendererDescriptor(
                        Set.of("runeteria:husbandry"),
                        Set.of("runeteria:checklist")),
                ignored -> {
                    rendererCreates.incrementAndGet();
                    return new TestController();
                });
        contributors.register(
                "runeteria:husbandry",
                new CommandUiContributorDescriptor(
                        Set.of("runeteria:husbandry/page"),
                        Set.of("runeteria:husbandry/row"),
                        Set.of(CommandUiContributorAction.Scope.PAGE,
                                CommandUiContributorAction.Scope.ROW),
                        Set.of("runeteria:checklist")),
                ignored -> emptyContributor());
        CommandUiCompositionResolver resolver = new CommandUiCompositionResolver(
                renderers, contributors);

        CommandUiCompositionResolver.Resolved resolved = resolver.resolve(
                CommandUiRendererId.of("runeteria:ui"),
                List.of(new CommandUiContributorRequirement(
                        CommandUiContributorId.of("runeteria:husbandry"), true)),
                new CommandUiOpenContext(), TestController::new);

        assertTrue(resolved.custom());
        assertEquals(1, rendererCreates.get());
        assertEquals(List.of(CommandUiContributorId.of("runeteria:husbandry")),
                resolved.contributors().stream()
                        .map(CommandUiCompositionSession.Binding::id).toList());
        assertTrue(resolved.contributorStatuses().isEmpty());
    }

    @Test
    void unsupportedRequiredContributorFallsBackBeforeRendererCreation() {
        CommandUiRendererRegistry renderers = new CommandUiRendererRegistry();
        CommandUiContributorRegistry contributors = new CommandUiContributorRegistry();
        AtomicInteger rendererCreates = new AtomicInteger();
        renderers.register(
                "runeteria:ui",
                new CommandUiRendererDescriptor(Set.of("runeteria:other"), Set.of()),
                ignored -> {
                    rendererCreates.incrementAndGet();
                    return new TestController();
                });
        contributors.register("runeteria:husbandry", ignored -> emptyContributor());
        CommandUiCompositionResolver resolver = new CommandUiCompositionResolver(
                renderers, contributors);

        CommandUiCompositionResolver.Resolved resolved = resolver.resolve(
                CommandUiRendererId.of("runeteria:ui"),
                List.of(new CommandUiContributorRequirement(
                        CommandUiContributorId.of("runeteria:husbandry"), true)),
                new CommandUiOpenContext(), TestController::new);

        assertFalse(resolved.custom());
        assertEquals(0, rendererCreates.get());
    }

    @Test
    void unsupportedOptionalContributorIsOmittedAndRecorded() {
        CommandUiRendererRegistry renderers = new CommandUiRendererRegistry();
        CommandUiContributorRegistry contributors = new CommandUiContributorRegistry();
        AtomicInteger rendererCreates = new AtomicInteger();
        renderers.register(
                "runeteria:ui",
                new CommandUiRendererDescriptor(Set.of("runeteria:other"), Set.of()),
                ignored -> {
                    rendererCreates.incrementAndGet();
                    return new TestController();
                });
        contributors.register("runeteria:husbandry", ignored -> emptyContributor());
        CommandUiCompositionResolver resolver = new CommandUiCompositionResolver(
                renderers, contributors);

        CommandUiCompositionResolver.Resolved resolved = resolver.resolve(
                CommandUiRendererId.of("runeteria:ui"),
                List.of(new CommandUiContributorRequirement(
                        CommandUiContributorId.of("runeteria:husbandry"), false)),
                new CommandUiOpenContext(), TestController::new);

        assertTrue(resolved.custom());
        assertEquals(1, rendererCreates.get());
        assertTrue(resolved.contributors().isEmpty());
        assertEquals(CommandUiContribution.Status.UNSUPPORTED_BY_RENDERER,
                resolved.contributorStatuses().get(
                        CommandUiContributorId.of("runeteria:husbandry")));
    }

    @Test
    void missingRequiredContributorChoosesStandardController() {
        CommandUiRendererRegistry renderers = new CommandUiRendererRegistry();
        CommandUiContributorRegistry contributors = new CommandUiContributorRegistry();
        CommandUiRendererProvider renderer = ignored -> new TestController();
        renderers.register("runeteria:ui", renderer);
        CommandUiCompositionResolver resolver = new CommandUiCompositionResolver(
                renderers, contributors);

        CommandUiCompositionResolver.Resolved resolved = resolver.resolve(
                CommandUiRendererId.of("runeteria:ui"),
                List.of(new CommandUiContributorRequirement(
                        CommandUiContributorId.of("runeteria:missing"), true)),
                new CommandUiOpenContext(),
                TestController::new);

        assertFalse(resolved.custom());
    }

    @Test
    void optionalMissingContributorKeepsTheSelectedRenderer() {
        CommandUiRendererRegistry renderers = new CommandUiRendererRegistry();
        CommandUiContributorRegistry contributors = new CommandUiContributorRegistry();
        renderers.register("runeteria:ui", ignored -> new TestController());
        CommandUiCompositionResolver resolver = new CommandUiCompositionResolver(
                renderers, contributors);

        CommandUiCompositionResolver.Resolved resolved = resolver.resolve(
                CommandUiRendererId.of("runeteria:ui"),
                List.of(new CommandUiContributorRequirement(
                        CommandUiContributorId.of("runeteria:missing"), false)),
                new CommandUiOpenContext(),
                TestController::new);

        assertTrue(resolved.custom());
        assertTrue(resolved.contributors().isEmpty());
    }

    @Test
    void missingRendererChoosesStandardController() {
        CommandUiCompositionResolver resolver = new CommandUiCompositionResolver(
                new CommandUiRendererRegistry(), new CommandUiContributorRegistry());

        CommandUiCompositionResolver.Resolved resolved = resolver.resolve(
                CommandUiRendererId.of("runeteria:missing"), List.of(),
                new CommandUiOpenContext(), TestController::new);

        assertFalse(resolved.custom());
    }

    private static final class TestController
            implements CommandUiPageController<TestEvent> {
        @Override
        public BuilderCodec<TestEvent> eventCodec() {
            return TestEvent.CODEC;
        }
    }

    private static final class TestEvent {
        private static final BuilderCodec<TestEvent> CODEC =
                BuilderCodec.builder(TestEvent.class, TestEvent::new).build();
    }

    private static CommandUiSessionContributor emptyContributor() {
        return new CommandUiSessionContributor() {
            @Override
            public CommandUiContribution compose(
                    CommandUiSnapshot base,
                    CommandUiContribution previous,
                    CommandUiDirtyScope scope
            ) {
                return new CommandUiContribution(
                        CommandUiContributorId.of("runeteria:husbandry"),
                        Map.of(), Map.of());
            }
        };
    }
}

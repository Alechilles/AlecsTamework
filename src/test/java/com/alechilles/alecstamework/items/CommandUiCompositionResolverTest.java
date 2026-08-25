package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiSessionContributor;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRendererRegistry;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Standard fallback and ordered composition resolution behavior. */
class CommandUiCompositionResolverTest {
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
}

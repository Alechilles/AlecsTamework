package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorDescriptor;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererDescriptor;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRendererRegistry;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renderer selection behavior before the host fixes its event codec. */
class CommandUiControllerResolverTest {
    @Test
    void forwardsOptionalCompatibilityStatusToSessionCreation() {
        CommandUiRendererRegistry renderers = new CommandUiRendererRegistry();
        CommandUiContributorRegistry contributors =
                new CommandUiContributorRegistry();
        CommandUiContributorId contributorId = CommandUiContributorId.of(
                "other:unsupported");
        renderers.register("runeteria:ui",
                new CommandUiRendererDescriptor(Set.of("runeteria"), Set.of()),
                ignored -> new TestController());
        contributors.register(contributorId.value(),
                new CommandUiContributorDescriptor(),
                ignored -> null);

        CommandUiControllerResolver.Resolved resolved =
                new CommandUiControllerResolver(renderers, contributors).resolve(
                        CommandUiRendererId.of("runeteria:ui"),
                        List.of(new CommandUiContributorRequirement(
                                contributorId, false)),
                        new CommandUiOpenContext(), TestController::new);

        assertTrue(resolved.custom());
        assertEquals(CommandUiContribution.Status.UNSUPPORTED_BY_RENDERER,
                resolved.contributorStatuses().get(contributorId));
    }

    @Test
    void registeredRendererWinsWithItsExactGeneration() {
        CommandUiRendererRegistry registry = new CommandUiRendererRegistry();
        TestController custom = new TestController();
        var registration = registry.register(
                "runeteria:husbandry", CommandUiRendererDescriptor.unrestricted(),
                ignored -> custom).registration();
        TestController standard = new TestController();

        CommandUiControllerResolver.Resolved resolved =
                new CommandUiControllerResolver(registry,
                        new CommandUiContributorRegistry()).resolve(
                        CommandUiRendererId.of("runeteria:husbandry"), List.of(),
                        new CommandUiOpenContext(),
                        () -> standard);

        assertSame(custom, resolved.controller());
        assertTrue(resolved.custom());
        assertEquals("runeteria:husbandry", resolved.rendererId().value());
        assertEquals(registration.generation(), resolved.rendererGeneration());
    }

    @Test
    void missingOrFailedRendererUsesAFreshStandardController() {
        CommandUiRendererRegistry registry = new CommandUiRendererRegistry();
        registry.register("example:broken",
                CommandUiRendererDescriptor.unrestricted(), ignored -> {
            throw new IllegalStateException("startup failed");
        });
        AtomicInteger standardCreations = new AtomicInteger();
        TestController standard = new TestController();
        CommandUiControllerResolver resolver = new CommandUiControllerResolver(
                registry, new CommandUiContributorRegistry());

        CommandUiControllerResolver.Resolved missing = resolver.resolve(
                CommandUiRendererId.of("example:missing"), List.of(),
                new CommandUiOpenContext(), () -> {
                    standardCreations.incrementAndGet();
                    return standard;
                });
        CommandUiControllerResolver.Resolved failed = resolver.resolve(
                CommandUiRendererId.of("example:broken"), List.of(),
                new CommandUiOpenContext(), () -> {
                    standardCreations.incrementAndGet();
                    return standard;
                });

        assertSame(standard, missing.controller());
        assertSame(standard, failed.controller());
        assertFalse(missing.custom());
        assertFalse(failed.custom());
        assertEquals(2, standardCreations.get());
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

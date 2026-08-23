package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provider selection behavior before the host fixes its event codec. */
class CommandUiControllerResolverTest {
    @Test
    void registeredProviderWinsWithItsExactGeneration() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        TestController custom = new TestController();
        var registration = registry.register(
                "runeteria:husbandry", ignored -> custom).registration();
        TestController standard = new TestController();

        CommandUiControllerResolver.Resolved resolved =
                new CommandUiControllerResolver(registry).resolve(
                        "  RUNETERIA:HUSBANDRY  ", new CommandUiOpenContext(),
                        () -> standard);

        assertSame(custom, resolved.controller());
        assertTrue(resolved.custom());
        assertEquals("runeteria:husbandry", resolved.providerId().value());
        assertEquals(registration.generation(), resolved.providerGeneration());
    }

    @Test
    void missingOrFailedProviderUsesAFreshStandardController() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        registry.register("example:broken", ignored -> {
            throw new IllegalStateException("startup failed");
        });
        AtomicInteger standardCreations = new AtomicInteger();
        TestController standard = new TestController();
        CommandUiControllerResolver resolver = new CommandUiControllerResolver(registry);

        CommandUiControllerResolver.Resolved missing = resolver.resolve(
                "example:missing", new CommandUiOpenContext(), () -> {
                    standardCreations.incrementAndGet();
                    return standard;
                });
        CommandUiControllerResolver.Resolved failed = resolver.resolve(
                "example:broken", new CommandUiOpenContext(), () -> {
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

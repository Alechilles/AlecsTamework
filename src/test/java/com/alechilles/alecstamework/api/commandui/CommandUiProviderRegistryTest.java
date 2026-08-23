package com.alechilles.alecstamework.api.commandui;

import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable registration behavior for the command UI provider facade. */
class CommandUiProviderRegistryTest {
    @Test
    void normalizesIdsAndKeepsFirstProviderOnDuplicate() {
        CommandUiProvider first = ignored -> null;
        CommandUiProvider second = ignored -> null;
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();

        CommandUiProviderRegistrationResult registered = registry.register(
                "  Runeteria:Husbandry  ", first
        );
        CommandUiProviderRegistrationResult duplicate = registry.register(
                "runeteria:husbandry", second
        );

        assertEquals(
                CommandUiProviderRegistrationResult.Status.REGISTERED,
                registered.status()
        );
        assertEquals(
                CommandUiProviderRegistrationResult.Status.CONFLICT,
                duplicate.status()
        );
        assertEquals(
                "runeteria:husbandry",
                registered.providerId().value()
        );
        assertSame(
                first,
                registry.find("RUNETERIA:HUSBANDRY").orElseThrow()
        );
        assertTrue(registered.registration().active());
    }

    @Test
    void closeRemovesOnlyItsGenerationAndIsIdempotent() {
        CommandUiProvider first = ignored -> null;
        CommandUiProvider second = ignored -> null;
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();

        CommandUiProviderRegistrationResult firstResult = registry.register(
                "example:menu", first
        );
        firstResult.registration().close();
        firstResult.registration().close();
        CommandUiProviderRegistrationResult secondResult = registry.register(
                "example:menu", second
        );
        firstResult.registration().close();

        assertFalse(firstResult.registration().active());
        assertTrue(secondResult.registration().active());
        assertSame(second, registry.find("example:menu").orElseThrow());

        registry.close();
        registry.close();
        assertFalse(registry.available());
        assertTrue(registry.find("example:menu").isEmpty());
    }

    @Test
    void rejectsInvalidAndReservedIdsWithoutMutatingRegistry() {
        CommandUiProviderRegistry registry = new CommandUiProviderRegistry();
        CommandUiProvider provider = ignored -> null;

        assertEquals(
                CommandUiProviderRegistrationResult.Status.INVALID,
                registry.register("menu", provider).status()
        );
        assertEquals(
                CommandUiProviderRegistrationResult.Status.INVALID,
                registry.register("tamework:standard", provider).status()
        );
        assertEquals(
                CommandUiProviderRegistrationResult.Status.INVALID,
                registry.register(" ", provider).status()
        );
        assertTrue(registry.listProviderIds().isEmpty());
    }

    @Test
    void unavailableFacadeFailsClosed() {
        CommandUiApi unavailable = CommandUiApi.unavailable();
        CommandUiProvider provider = ignored -> null;

        assertFalse(unavailable.available());
        assertEquals(
                CommandUiProviderRegistrationResult.Status.UNAVAILABLE,
                unavailable.register("example:menu", provider).status()
        );
        assertEquals(Optional.empty(), unavailable.find("example:menu"));
    }
}

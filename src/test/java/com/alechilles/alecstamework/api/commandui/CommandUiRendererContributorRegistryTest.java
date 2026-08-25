package com.alechilles.alecstamework.api.commandui;

import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRendererRegistry;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable renderer and contributor registration behavior. */
class CommandUiRendererContributorRegistryTest {
    @Test
    void rendererRegistrationKeepsTheFirstGenerationOnConflict() {
        CommandUiRendererProvider first = ignored -> null;
        CommandUiRendererProvider second = ignored -> null;
        CommandUiRegistry registry = new CommandUiRegistry(
                new CommandUiRendererRegistry(), new CommandUiContributorRegistry());

        CommandUiRegistrationResult registered = registry.registerRenderer(
                " Runeteria:Conformance ", first);
        CommandUiRegistrationResult conflict = registry.registerRenderer(
                "runeteria:conformance", second);

        assertEquals(CommandUiRegistrationResult.Status.REGISTERED,
                registered.status());
        assertEquals(CommandUiRegistrationResult.Status.CONFLICT,
                conflict.status());
        assertSame(first, registry.findRenderer("RUNETERIA:CONFORMANCE")
                .orElseThrow());
        assertTrue(registered.registration().active());
    }

    @Test
    void closingAnOldGenerationDoesNotRemoveItsReplacement() {
        CommandUiRegistry registry = new CommandUiRegistry(
                new CommandUiRendererRegistry(), new CommandUiContributorRegistry());
        CommandUiRegistrationResult first = registry.registerRenderer(
                "example:menu", ignored -> null);
        first.registration().close();
        CommandUiRegistrationResult replacement = registry.registerRenderer(
                "example:menu", ignored -> null);

        first.registration().close();

        assertFalse(first.registration().active());
        assertTrue(replacement.registration().active());
        assertTrue(registry.findRenderer("example:menu").isPresent());
        assertTrue(replacement.registration().generation()
                > first.registration().generation());
    }

    @Test
    void contributorRegistrationUsesTheSameConflictAndCloseContract() {
        CommandUiContributorProvider provider = ignored -> emptyContributor();
        CommandUiRegistry registry = new CommandUiRegistry(
                new CommandUiRendererRegistry(), new CommandUiContributorRegistry());

        CommandUiRegistrationResult registered = registry.registerContributor(
                "runeteria:husbandry", provider);
        CommandUiRegistrationResult conflict = registry.registerContributor(
                "runeteria:husbandry", ignored -> emptyContributor());

        assertEquals(CommandUiRegistrationResult.Status.REGISTERED,
                registered.status());
        assertEquals(CommandUiRegistrationResult.Status.CONFLICT,
                conflict.status());
        assertSame(provider, registry.findContributor("runeteria:husbandry")
                .orElseThrow());

        registered.registration().close();
        registered.registration().close();
        assertTrue(registry.findContributor("runeteria:husbandry").isEmpty());
    }

    @Test
    void contributorRemovalListenerIsGenerationSpecific() throws Exception {
        CommandUiRegistry registry = new CommandUiRegistry(
                new CommandUiRendererRegistry(), new CommandUiContributorRegistry());
        List<String> removed = new java.util.ArrayList<>();
        AutoCloseable listener = registry.contributorRegistry().subscribeUnregister(
                (id, generation) -> removed.add(id.value() + "@" + generation));
        CommandUiRegistrationResult first = registry.registerContributor(
                "example:data", ignored -> emptyContributor());
        first.registration().close();
        CommandUiRegistrationResult replacement = registry.registerContributor(
                "example:data", ignored -> emptyContributor());

        replacement.registration().close();
        listener.close();

        assertEquals(List.of("example:data@" + first.registration().generation(),
                "example:data@" + replacement.registration().generation()), removed);
    }

    @Test
    void unavailableFacadeFailsClosedForBothRegistrationSurfaces() {
        CommandUiApi unavailable = CommandUiApi.unavailable();

        assertFalse(unavailable.available());
        assertEquals(CommandUiRegistrationResult.Status.UNAVAILABLE,
                unavailable.registerRenderer("example:menu", ignored -> null)
                        .status());
        assertEquals(CommandUiRegistrationResult.Status.UNAVAILABLE,
                unavailable.registerContributor("example:data", ignored -> null)
                        .status());
        assertEquals(Optional.empty(), unavailable.find("example:menu"));
    }

    private static CommandUiSessionContributor emptyContributor() {
        return new CommandUiSessionContributor() {
            @Override
            public CommandUiContribution compose(
                    CommandUiSnapshot base,
                    CommandUiContribution previous
            ) {
                return new CommandUiContribution(
                        CommandUiContributorId.of("example:test"));
            }
        };
    }
}

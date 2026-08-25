package com.alechilles.alecstamework.api.commandhud;

import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Observable registration and generation behavior for command HUD adapters. */
class CommandHudRegistryTest {
    @Test
    void targetRendererStaleCloseDoesNotRemoveReplacementGeneration() {
        CommandTargetHudRendererProvider provider = ignored -> null;
        CommandHudRegistry registry = new CommandHudRegistry();

        CommandHudRegistrationResult first = registry.registerTargetRenderer(
                "runeteria:target", CommandHudRendererDescriptor.unrestricted(), provider);
        first.registration().close();
        CommandHudRegistrationResult second = registry.registerTargetRenderer(
                "runeteria:target", CommandHudRendererDescriptor.unrestricted(), provider);

        first.registration().close();

        assertFalse(first.registration().active());
        assertTrue(second.registration().active());
        assertTrue(registry.resolveTargetRenderer("RUNETERIA:TARGET").isPresent());
        assertTrue(second.registration().generation() > first.registration().generation());
    }

    @Test
    void hotswapRendererAndContributorsKeepIndependentGenerations() {
        CommandHotswapHudRendererProvider renderer = ignored -> null;
        CommandTargetHudContributorProvider targetContributor = ignored -> null;
        CommandHotswapHudContributorProvider hotswapContributor = ignored -> null;
        CommandHudRegistry registry = new CommandHudRegistry();

        CommandHudRegistrationResult hotswap = registry.registerHotswapRenderer(
                "runeteria:hotswap", CommandHudRendererDescriptor.unrestricted(), renderer);
        CommandHudRegistrationResult targetData = registry.registerTargetContributor(
                "runeteria:target-data", CommandHudContributorDescriptor.unrestricted(),
                targetContributor);
        CommandHudRegistrationResult hotswapData = registry.registerHotswapContributor(
                "runeteria:hotswap-data", CommandHudContributorDescriptor.unrestricted(),
                hotswapContributor);

        assertSame(renderer, registry.resolveHotswapRenderer("runeteria:hotswap")
                .orElseThrow().provider());
        assertSame(targetContributor, registry.resolveTargetContributor("runeteria:target-data")
                .orElseThrow().provider());
        assertSame(hotswapContributor,
                registry.resolveHotswapContributor("runeteria:hotswap-data")
                        .orElseThrow().provider());

        hotswap.registration().close();
        targetData.registration().close();
        hotswapData.registration().close();
        assertTrue(registry.diagnostics().targetRenderers().isEmpty());
        assertTrue(registry.diagnostics().hotswapRenderers().isEmpty());
        assertTrue(registry.diagnostics().targetContributors().isEmpty());
        assertTrue(registry.diagnostics().hotswapContributors().isEmpty());
    }

    @Test
    void duplicateIdsReturnConflictAndDiagnosticsContainLiveGenerations() {
        CommandHudRegistry registry = new CommandHudRegistry();
        CommandHudRegistrationResult renderer = registry.registerTargetRenderer(
                "Runeteria:Target", CommandHudRendererDescriptor.unrestricted(),
                ignored -> null);
        CommandHudRegistrationResult conflict = registry.registerTargetRenderer(
                "runeteria:target", CommandHudRendererDescriptor.unrestricted(),
                ignored -> null);
        CommandHudRegistrationResult contributor = registry.registerTargetContributor(
                "Runeteria:Data", CommandHudContributorDescriptor.unrestricted(),
                ignored -> null);

        assertEquals(CommandHudRegistrationResult.Status.REGISTERED, renderer.status());
        assertEquals(CommandHudRegistrationResult.Status.CONFLICT, conflict.status());
        assertEquals(CommandHudRegistrationResult.Status.REGISTERED, contributor.status());
        assertEquals(List.of(new CommandHudDiagnostics.RendererRegistration(
                        "runeteria:target", renderer.registration().generation())),
                registry.diagnostics().targetRenderers());
        assertEquals(List.of(new CommandHudDiagnostics.ContributorRegistration(
                        "runeteria:data", contributor.registration().generation())),
                registry.diagnostics().targetContributors());
    }

    @Test
    void unavailableFacadeFailsClosedForAllFourRegistrationRoutes() {
        CommandHudApi unavailable = CommandHudApi.unavailable();

        assertFalse(unavailable.available());
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE,
                unavailable.registerTargetRenderer("example:target", ignored -> null).status());
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE,
                unavailable.registerHotswapRenderer("example:hotswap", ignored -> null).status());
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE,
                unavailable.registerTargetContributor("example:target-data", ignored -> null)
                        .status());
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE,
                unavailable.registerHotswapContributor("example:hotswap-data", ignored -> null)
                        .status());
        assertTrue(unavailable.diagnostics().targetRenderers().isEmpty());
    }
}

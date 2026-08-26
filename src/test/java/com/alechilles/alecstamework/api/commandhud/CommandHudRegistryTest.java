package com.alechilles.alecstamework.api.commandhud;

import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.api.internal.CommandHudDiagnosticsRuntime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    void hotswapRendererStaleCloseDoesNotRemoveReplacementGeneration() {
        CommandHotswapHudRendererProvider provider = ignored -> null;
        CommandHudRegistry registry = new CommandHudRegistry();

        CommandHudRegistrationResult first = registry.registerHotswapRenderer(
                "runeteria:hotswap", CommandHudRendererDescriptor.unrestricted(), provider);
        first.registration().close();
        CommandHudRegistrationResult second = registry.registerHotswapRenderer(
                "runeteria:hotswap", CommandHudRendererDescriptor.unrestricted(), provider);

        first.registration().close();

        assertFalse(first.registration().active());
        assertTrue(second.registration().active());
        assertTrue(registry.resolveHotswapRenderer("RUNETERIA:HOTSWAP").isPresent());
    }

    @Test
    void targetContributorStaleCloseDoesNotRemoveReplacementGeneration() {
        CommandTargetHudContributorProvider provider = ignored -> null;
        CommandHudRegistry registry = new CommandHudRegistry();

        CommandHudRegistrationResult first = registry.registerTargetContributor(
                "runeteria:target-data", CommandHudContributorDescriptor.unrestricted(), provider);
        first.registration().close();
        CommandHudRegistrationResult second = registry.registerTargetContributor(
                "runeteria:target-data", CommandHudContributorDescriptor.unrestricted(), provider);

        first.registration().close();

        assertFalse(first.registration().active());
        assertTrue(second.registration().active());
        assertTrue(registry.resolveTargetContributor("RUNETERIA:TARGET-DATA").isPresent());
    }

    @Test
    void hotswapContributorStaleCloseDoesNotRemoveReplacementGeneration() {
        CommandHotswapHudContributorProvider provider = ignored -> null;
        CommandHudRegistry registry = new CommandHudRegistry();

        CommandHudRegistrationResult first = registry.registerHotswapContributor(
                "runeteria:hotswap-data", CommandHudContributorDescriptor.unrestricted(), provider);
        first.registration().close();
        CommandHudRegistrationResult second = registry.registerHotswapContributor(
                "runeteria:hotswap-data", CommandHudContributorDescriptor.unrestricted(), provider);

        first.registration().close();

        assertFalse(first.registration().active());
        assertTrue(second.registration().active());
        assertTrue(registry.resolveHotswapContributor("RUNETERIA:HOTSWAP-DATA").isPresent());
    }

    @Test
    void exactContributorIdSupportDoesNotRequireMatchingDataNamespace() {
        CommandHudRendererDescriptor renderer = new CommandHudRendererDescriptor(
                Set.of("runeteria:health"));
        CommandHudContributorDescriptor contributor = new CommandHudContributorDescriptor(
                Set.of("other:stats"));

        assertTrue(renderer.supports(
                CommandHudContributorId.of("runeteria:health"), contributor));
        assertFalse(renderer.supports(
                CommandHudContributorId.of("runeteria:other"), contributor));
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
        CommandTargetHudRendererProvider targetRenderer = ignored -> null;
        CommandHotswapHudRendererProvider hotswapRenderer = ignored -> null;
        CommandTargetHudContributorProvider targetContributor = ignored -> null;
        CommandHotswapHudContributorProvider hotswapContributor = ignored -> null;
        CommandHudRendererDescriptor rendererDescriptor =
                CommandHudRendererDescriptor.unrestricted();
        CommandHudContributorDescriptor contributorDescriptor =
                CommandHudContributorDescriptor.unrestricted();

        assertFalse(unavailable.available());
        assertUnavailable(unavailable.registerTargetRenderer("example:target", targetRenderer));
        assertUnavailable(unavailable.registerTargetRenderer(
                "example:target-descriptor", rendererDescriptor, targetRenderer));
        assertUnavailable(unavailable.registerTargetRenderer(
                CommandHudRendererId.of("example:target-typed"), targetRenderer));
        assertUnavailable(unavailable.registerTargetRenderer(
                CommandHudRendererId.of("example:target-typed-descriptor"),
                rendererDescriptor, targetRenderer));
        assertUnavailable(unavailable.registerHotswapRenderer(
                "example:hotswap", hotswapRenderer));
        assertUnavailable(unavailable.registerHotswapRenderer(
                "example:hotswap-descriptor", rendererDescriptor, hotswapRenderer));
        assertUnavailable(unavailable.registerHotswapRenderer(
                CommandHudRendererId.of("example:hotswap-typed"), hotswapRenderer));
        assertUnavailable(unavailable.registerHotswapRenderer(
                CommandHudRendererId.of("example:hotswap-typed-descriptor"),
                rendererDescriptor, hotswapRenderer));
        assertUnavailable(unavailable.registerTargetContributor(
                "example:target-data", targetContributor));
        assertUnavailable(unavailable.registerTargetContributor(
                "example:target-data-descriptor", contributorDescriptor, targetContributor));
        assertUnavailable(unavailable.registerTargetContributor(
                CommandHudContributorId.of("example:target-data-typed"), targetContributor));
        assertUnavailable(unavailable.registerTargetContributor(
                CommandHudContributorId.of("example:target-data-typed-descriptor"),
                contributorDescriptor, targetContributor));
        assertUnavailable(unavailable.registerHotswapContributor(
                "example:hotswap-data", hotswapContributor));
        assertUnavailable(unavailable.registerHotswapContributor(
                "example:hotswap-data-descriptor", contributorDescriptor, hotswapContributor));
        assertUnavailable(unavailable.registerHotswapContributor(
                CommandHudContributorId.of("example:hotswap-data-typed"), hotswapContributor));
        assertUnavailable(unavailable.registerHotswapContributor(
                CommandHudContributorId.of("example:hotswap-data-typed-descriptor"),
                contributorDescriptor, hotswapContributor));
        assertTrue(unavailable.diagnostics().targetRenderers().isEmpty());
    }

    @Test
    void closedRegistryReturnsDetachedEmptyDiagnostics() {
        CommandHudRegistry registry = new CommandHudRegistry();
        registry.registerTargetRenderer("runeteria:target", ignored -> null);

        CommandHudDiagnostics beforeClose = registry.diagnostics();
        registry.close();
        CommandHudDiagnostics afterClose = registry.diagnostics();

        assertFalse(registry.available());
        assertEquals(1, beforeClose.targetRenderers().size());
        assertNotSame(beforeClose, afterClose);
        assertTrue(afterClose.targetRenderers().isEmpty());
        assertTrue(afterClose.hotswapRenderers().isEmpty());
        assertTrue(afterClose.targetContributors().isEmpty());
        assertTrue(afterClose.hotswapContributors().isEmpty());
    }

    @Test
    void publicDiagnosticsMergeLiveRegistrationsWithDetachedRuntimeSessions() {
        CommandHudRegistry registry = new CommandHudRegistry();
        CommandHudRegistrationResult renderer = registry.registerTargetRenderer(
                "runeteria:target", ignored -> null);
        CommandHudRegistrationResult contributor = registry.registerTargetContributor(
                "runeteria:data", ignored -> null);
        UUID sessionId = UUID.randomUUID();
        CommandHudDiagnostics runtime = new CommandHudDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(new CommandHudDiagnostics.SessionView(
                        sessionId, CommandHudSurface.TARGET, "runeteria:target",
                        renderer.registration().generation(), List.of(),
                        "item", "config", null)), null, 0L, 0L);
        registry.diagnosticsRuntime().connect(() -> runtime);

        CommandHudDiagnostics diagnostics = registry.diagnostics();

        assertEquals(1, diagnostics.targetRenderers().size());
        assertEquals(1, diagnostics.targetContributors().size());
        assertEquals(1, diagnostics.activeSessionCount());
        assertEquals(sessionId, diagnostics.sessions().get(0).sessionId());

        registry.close();

        assertTrue(registry.diagnostics().targetRenderers().isEmpty());
        assertTrue(registry.diagnostics().targetContributors().isEmpty());
        assertTrue(registry.diagnostics().sessions().isEmpty());
        assertTrue(registry.diagnosticsRuntime().snapshot().sessions().isEmpty());
    }

    @Test
    void runtimeDiagnosticsAggregatesLiveSourcesAndIgnoresTemporaryProbeSource() {
        CommandHudDiagnosticsRuntime runtime = new CommandHudDiagnosticsRuntime();
        UUID targetSession = UUID.randomUUID();
        UUID hotswapSession = UUID.randomUUID();
        runtime.connect(new Object(), () -> diagnosticsFor(
                targetSession, CommandHudSurface.TARGET, "runeteria:target"));
        runtime.connect(new Object(), () -> diagnosticsFor(
                hotswapSession, CommandHudSurface.HOTSWAP, "runeteria:hotswap"));

        runtime.connect(new Object(), CommandHudDiagnostics::empty);

        CommandHudDiagnostics merged = runtime.snapshot();

        assertEquals(2, merged.activeSessionCount());
        assertEquals(Set.of(targetSession, hotswapSession), merged.sessions().stream()
                .map(CommandHudDiagnostics.SessionView::sessionId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(CommandHudSurface.TARGET, CommandHudSurface.HOTSWAP),
                merged.sessions().stream().map(CommandHudDiagnostics.SessionView::surface)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private static CommandHudDiagnostics diagnosticsFor(
            UUID sessionId,
            CommandHudSurface surface,
            String rendererId
    ) {
        return new CommandHudDiagnostics(
                List.of(), List.of(), List.of(), List.of(),
                List.of(new CommandHudDiagnostics.SessionView(
                        sessionId, surface, rendererId, 1L, List.of(),
                        "item", "config", null)), null, 0L, 0L);
    }

    private static void assertUnavailable(CommandHudRegistrationResult result) {
        assertEquals(CommandHudRegistrationResult.Status.UNAVAILABLE, result.status());
    }
}

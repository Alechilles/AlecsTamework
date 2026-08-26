package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributionStatus;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudRendererProvider;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudRendererProvider;
import com.alechilles.alecstamework.api.internal.CommandHudContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandHudRendererRegistry;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Standard fallback and surface-local HUD composition resolution. */
class CommandHudCompositionResolverTest {
    @Test
    void absentRendererReturnsStandardFallbackDecision() {
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                new CommandHudRendererRegistry(), new CommandHudContributorRegistry());

        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:missing", List.of());

        assertFalse(resolution.custom());
        assertTrue(resolution.contributors().isEmpty());
    }

    @Test
    void missingRequiredContributorReturnsStandardFallbackDecision() {
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", targetController());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, new CommandHudContributorRegistry());

        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer",
                List.of(new CommandHudContributorRequirement("example:required", true)));

        assertFalse(resolution.custom());
    }

    @Test
    void missingOptionalContributorProducesUnavailableContribution() {
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget("example:renderer", targetController());
        CommandHudContributorId optionalId = CommandHudContributorId.of("example:optional");
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, new CommandHudContributorRegistry());

        CommandHudTargetResolution resolution = resolver.resolveTarget(
                "example:renderer",
                List.of(new CommandHudContributorRequirement(optionalId, false)));

        assertTrue(resolution.custom());
        assertEquals(CommandHudContributionStatus.UNAVAILABLE,
                resolution.contributions().get(optionalId).status());
    }

    @Test
    void rendererContributorMismatchFallsBackOnlyTheAffectedSurface() {
        CommandHudRendererRegistry renderers = new CommandHudRendererRegistry();
        renderers.registerTarget(
                "example:target",
                new CommandHudRendererDescriptor(Set.of("example:other")),
                targetController());
        renderers.registerHotswap("example:hotswap", hotswapController());
        CommandHudContributorRegistry contributors = new CommandHudContributorRegistry();
        contributors.registerTarget(
                "example:badge",
                new CommandHudContributorDescriptor(Set.of("example:badge/value")),
                context -> ignoredTargetContributor());
        contributors.registerHotswap(
                "example:badge",
                new CommandHudContributorDescriptor(Set.of("example:badge/value")),
                context -> ignoredHotswapContributor());
        CommandHudCompositionResolver resolver = new CommandHudCompositionResolver(
                renderers, contributors);

        CommandHudTargetResolution target = resolver.resolveTarget(
                "example:target",
                List.of(new CommandHudContributorRequirement("example:badge", true)));
        CommandHudHotswapResolution hotswap = resolver.resolveHotswap(
                "example:hotswap",
                List.of(new CommandHudContributorRequirement("example:badge", true)));

        assertFalse(target.custom());
        assertTrue(hotswap.custom());
        assertEquals(CommandHudSurface.HOTSWAP, hotswap.surface());
    }

    private static CommandTargetHudRendererProvider targetController() {
        return ignored -> new CommandTargetHudController() {
            @Override
            public void buildInitial(
                    com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext context,
                    com.alechilles.alecstamework.api.commandhud.CommandTargetHudView view,
                    UICommandBuilder commands
            ) {
            }
        };
    }

    private static CommandHotswapHudRendererProvider hotswapController() {
        return ignored -> new CommandHotswapHudController() {
            @Override
            public void buildInitial(
                    com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext context,
                    com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView view,
                    UICommandBuilder commands
            ) {
            }
        };
    }

    private static com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor
    ignoredTargetContributor() {
        return (base, previous, scope) ->
                new com.alechilles.alecstamework.api.commandhud.CommandHudContribution(
                        CommandHudContributorId.of("example:badge"), Map.of());
    }

    private static com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSessionContributor
    ignoredHotswapContributor() {
        return (base, previous, scope) ->
                new com.alechilles.alecstamework.api.commandhud.CommandHudContribution(
                        CommandHudContributorId.of("example:badge"), Map.of());
    }
}

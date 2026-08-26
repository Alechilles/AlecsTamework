package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererId;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudContributorProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudRendererProvider;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudContributorProvider;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudRendererProvider;
import com.alechilles.alecstamework.api.internal.CommandHudContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.api.internal.CommandHudRendererRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves one exact renderer generation and its ordered HUD contributors. */
final class CommandHudCompositionResolver {
    private final CommandHudRendererRegistry renderers;
    private final CommandHudContributorRegistry contributors;
    final CommandHudDiagnosticsService diagnostics;
    final CommandHudTimingWarnings timingWarnings;

    CommandHudCompositionResolver(
            @Nonnull CommandHudRendererRegistry renderers,
            @Nonnull CommandHudContributorRegistry contributors
    ) {
        this(renderers, contributors, new CommandHudTimingWarnings());
    }

    CommandHudCompositionResolver(@Nonnull CommandHudRegistry registry) {
        this(Objects.requireNonNull(registry, "registry").rendererRegistry(),
                registry.contributorRegistry(), new CommandHudTimingWarnings());
        registry.diagnosticsRuntime().connect(diagnostics::snapshot);
    }

    CommandHudCompositionResolver(
            @Nonnull CommandHudRendererRegistry renderers,
            @Nonnull CommandHudContributorRegistry contributors,
            @Nonnull CommandHudTimingWarnings timingWarnings
    ) {
        this.renderers = Objects.requireNonNull(renderers, "renderers");
        this.contributors = Objects.requireNonNull(contributors, "contributors");
        this.timingWarnings = Objects.requireNonNull(timingWarnings, "timingWarnings");
        this.diagnostics = new CommandHudDiagnosticsService(this.timingWarnings);
    }

    CommandHudCompositionResolver(
            @Nonnull CommandHudRendererRegistry renderers,
            @Nonnull CommandHudContributorRegistry contributors,
            @Nonnull CommandHudDiagnosticsService diagnostics,
            @Nonnull CommandHudTimingWarnings timingWarnings
    ) {
        this.renderers = Objects.requireNonNull(renderers, "renderers");
        this.contributors = Objects.requireNonNull(contributors, "contributors");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.timingWarnings = Objects.requireNonNull(timingWarnings, "timingWarnings");
    }

    /** Resolves the configured target surface without creating live state. */
    @Nonnull
    CommandHudTargetResolution resolveTarget(
            @Nullable String rendererId,
            @Nonnull List<CommandHudContributorRequirement> requirements
    ) {
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rendererId);
        return parsed.isEmpty() ? CommandHudTargetResolution.standard()
                : resolveTarget(parsed.orElseThrow(), requirements);
    }

    /** Resolves the target surface for a parsed renderer ID. */
    @Nonnull
    CommandHudTargetResolution resolveTarget(
            @Nullable CommandHudRendererId rendererId,
            @Nonnull List<CommandHudContributorRequirement> requirements
    ) {
        Objects.requireNonNull(requirements, "requirements");
        if (rendererId == null) return CommandHudTargetResolution.standard();
        Optional<CommandHudRendererRegistry.ResolvedTargetRenderer> renderer =
                renderers.resolveTarget(rendererId.value());
        if (renderer.isEmpty()) return CommandHudTargetResolution.standard();
        CommandHudRendererRegistry.ResolvedTargetRenderer selected = renderer.orElseThrow();
        List<CommandHudCompositionBinding<
                com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot>> bindings =
                new ArrayList<>();
        Map<com.alechilles.alecstamework.api.commandhud.CommandHudContributorId,
                CommandHudContribution> compatibility = new LinkedHashMap<>();
        for (CommandHudContributorRequirement requirement : requirements) {
            if (requirement == null || requirement.id() == null) continue;
            Optional<CommandHudContributorRegistry.ResolvedTargetContributor> contributor =
                    contributors.resolveTarget(requirement.id().value());
            if (contributor.isEmpty()) {
                if (requirement.required()) return CommandHudTargetResolution.standard();
                compatibility.put(requirement.id(), CommandHudContribution.unavailable(
                        requirement.id(), "optional contributor is not registered"));
                continue;
            }
            CommandHudContributorRegistry.ResolvedTargetContributor value = contributor.orElseThrow();
            if (!selected.descriptor().supports(value.id(), value.descriptor())) {
                if (requirement.required()) return CommandHudTargetResolution.standard();
                compatibility.put(value.id(), CommandHudContribution.unsupported(
                        value.id(), "selected renderer does not support contributor"));
                continue;
            }
            bindings.add(CommandHudCompositionBinding.target(
                    value.id(), value.generation(), value.provider(), requirement.required(), contributors));
        }
        return new CommandHudTargetResolution(selected.id(), selected.provider(), selected.generation(),
                true, bindings, compatibility,
                () -> renderers.isTargetActive(selected.id(), selected.generation()), renderers);
    }

    /** Resolves target selection fields from an effective command config. */
    @Nonnull
    CommandHudTargetResolution resolveTarget(@Nullable TwCommandItemConfig config) {
        return config == null ? CommandHudTargetResolution.standard()
                : resolveTarget(config.getTargetHudRendererId(), config.getTargetHudContributors());
    }

    /** Resolves the configured hotswap surface without creating live state. */
    @Nonnull
    CommandHudHotswapResolution resolveHotswap(
            @Nullable String rendererId,
            @Nonnull List<CommandHudContributorRequirement> requirements
    ) {
        Optional<CommandHudRendererId> parsed = CommandHudRendererId.tryParse(rendererId);
        return parsed.isEmpty() ? CommandHudHotswapResolution.standard()
                : resolveHotswap(parsed.orElseThrow(), requirements);
    }

    /** Resolves the hotswap surface for a parsed renderer ID. */
    @Nonnull
    CommandHudHotswapResolution resolveHotswap(
            @Nullable CommandHudRendererId rendererId,
            @Nonnull List<CommandHudContributorRequirement> requirements
    ) {
        Objects.requireNonNull(requirements, "requirements");
        if (rendererId == null) return CommandHudHotswapResolution.standard();
        Optional<CommandHudRendererRegistry.ResolvedHotswapRenderer> renderer =
                renderers.resolveHotswap(rendererId.value());
        if (renderer.isEmpty()) return CommandHudHotswapResolution.standard();
        CommandHudRendererRegistry.ResolvedHotswapRenderer selected = renderer.orElseThrow();
        List<CommandHudCompositionBinding<
                com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot>> bindings =
                new ArrayList<>();
        Map<com.alechilles.alecstamework.api.commandhud.CommandHudContributorId,
                CommandHudContribution> compatibility = new LinkedHashMap<>();
        for (CommandHudContributorRequirement requirement : requirements) {
            if (requirement == null || requirement.id() == null) continue;
            Optional<CommandHudContributorRegistry.ResolvedHotswapContributor> contributor =
                    contributors.resolveHotswap(requirement.id().value());
            if (contributor.isEmpty()) {
                if (requirement.required()) return CommandHudHotswapResolution.standard();
                compatibility.put(requirement.id(), CommandHudContribution.unavailable(
                        requirement.id(), "optional contributor is not registered"));
                continue;
            }
            CommandHudContributorRegistry.ResolvedHotswapContributor value = contributor.orElseThrow();
            if (!selected.descriptor().supports(value.id(), value.descriptor())) {
                if (requirement.required()) return CommandHudHotswapResolution.standard();
                compatibility.put(value.id(), CommandHudContribution.unsupported(
                        value.id(), "selected renderer does not support contributor"));
                continue;
            }
            bindings.add(CommandHudCompositionBinding.hotswap(
                    value.id(), value.generation(), value.provider(), requirement.required(), contributors));
        }
        return new CommandHudHotswapResolution(selected.id(), selected.provider(), selected.generation(),
                true, bindings, compatibility,
                () -> renderers.isHotswapActive(selected.id(), selected.generation()), renderers);
    }

    /** Resolves hotswap selection fields from an effective command config. */
    @Nonnull
    CommandHudHotswapResolution resolveHotswap(@Nullable TwCommandItemConfig config) {
        return config == null ? CommandHudHotswapResolution.standard()
                : resolveHotswap(config.getHotswapHudRendererId(), config.getHotswapHudContributors());
    }

    /** Opens a target composition session bound to the selected generations. */
    public CommandHudCompositionSession<
            com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot,
            com.alechilles.alecstamework.api.commandhud.CommandTargetHudView,
            com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate> openTarget(
            @Nonnull com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext context,
            @Nonnull CommandHudTargetResolution resolved
    ) {
        return CommandHudCompositionSession.target(context, resolved, diagnostics, timingWarnings);
    }

    /** Opens a hotswap composition session bound to the selected generations. */
    public CommandHudCompositionSession<
            com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot,
            com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView,
            com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate> openHotswap(
            @Nonnull com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext context,
            @Nonnull CommandHudHotswapResolution resolved
    ) {
        return CommandHudCompositionSession.hotswap(context, resolved, diagnostics, timingWarnings);
    }

    @Nonnull
    CommandHudDiagnosticsService diagnostics() {
        return diagnostics;
    }
}

package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudContributorProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudRendererProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHudApi;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudDiagnostics;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererDescriptor;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererId;
import com.alechilles.alecstamework.api.commandhud.CommandHudRegistrationResult;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudContributorProvider;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudRendererProvider;
import com.alechilles.alecstamework.items.CommandHudDiagnosticsService;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Small public facade over independent target and hotswap HUD registries. */
public final class CommandHudRegistry implements CommandHudApi, AutoCloseable {
    private final CommandHudRendererRegistry renderers;
    private final CommandHudContributorRegistry contributors;
    private final CommandHudDiagnosticsService diagnostics =
            new CommandHudDiagnosticsService();

    /** Creates an empty registry for both command HUD surfaces. */
    public CommandHudRegistry() {
        this(new CommandHudRendererRegistry(), new CommandHudContributorRegistry());
    }

    /** Creates a facade over injected renderer and contributor registries. */
    public CommandHudRegistry(
            @Nonnull CommandHudRendererRegistry renderers,
            @Nonnull CommandHudContributorRegistry contributors
    ) {
        this.renderers = Objects.requireNonNull(renderers, "renderers");
        this.contributors = Objects.requireNonNull(contributors, "contributors");
    }

    @Override
    public boolean available() {
        return renderers.available() && contributors.available();
    }

    @Override
    @Nonnull
    public CommandHudRegistrationResult registerTargetRenderer(
            @Nullable String rendererId,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        return registerTargetRenderer(rendererId,
                CommandHudRendererDescriptor.unrestricted(), provider);
    }

    @Override
    @Nonnull
    public CommandHudRegistrationResult registerTargetRenderer(
            @Nullable String rendererId,
            @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        return renderers.registerTarget(rendererId, descriptor, provider);
    }

    @Override
    @Nonnull
    public CommandHudRegistrationResult registerHotswapRenderer(
            @Nullable String rendererId,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        return registerHotswapRenderer(rendererId,
                CommandHudRendererDescriptor.unrestricted(), provider);
    }

    @Override
    @Nonnull
    public CommandHudRegistrationResult registerHotswapRenderer(
            @Nullable String rendererId,
            @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        return renderers.registerHotswap(rendererId, descriptor, provider);
    }

    @Override
    @Nonnull
    public CommandHudRegistrationResult registerTargetContributor(
            @Nullable String contributorId,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        return registerTargetContributor(contributorId,
                CommandHudContributorDescriptor.unrestricted(), provider);
    }

    @Override
    @Nonnull
    public CommandHudRegistrationResult registerTargetContributor(
            @Nullable String contributorId,
            @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        return contributors.registerTarget(contributorId, descriptor, provider);
    }

    @Override
    @Nonnull
    public CommandHudRegistrationResult registerHotswapContributor(
            @Nullable String contributorId,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        return registerHotswapContributor(contributorId,
                CommandHudContributorDescriptor.unrestricted(), provider);
    }

    @Override
    @Nonnull
    public CommandHudRegistrationResult registerHotswapContributor(
            @Nullable String contributorId,
            @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        return contributors.registerHotswap(contributorId, descriptor, provider);
    }

    /** Finds a target renderer provider for internal HUD composition. */
    @Nonnull
    public Optional<CommandTargetHudRendererProvider> findTargetRenderer(
            @Nullable String rendererId
    ) {
        return renderers.findTarget(rendererId);
    }

    /** Finds a hotswap renderer provider for internal HUD composition. */
    @Nonnull
    public Optional<CommandHotswapHudRendererProvider> findHotswapRenderer(
            @Nullable String rendererId
    ) {
        return renderers.findHotswap(rendererId);
    }

    /** Resolves a target renderer with its exact descriptor and generation. */
    @Nonnull
    public Optional<CommandHudRendererRegistry.ResolvedTargetRenderer>
    resolveTargetRenderer(@Nullable String rendererId) {
        return renderers.resolveTarget(rendererId);
    }

    /** Resolves a hotswap renderer with its exact descriptor and generation. */
    @Nonnull
    public Optional<CommandHudRendererRegistry.ResolvedHotswapRenderer>
    resolveHotswapRenderer(@Nullable String rendererId) {
        return renderers.resolveHotswap(rendererId);
    }

    /** Finds a target contributor provider for internal HUD composition. */
    @Nonnull
    public Optional<CommandTargetHudContributorProvider> findTargetContributor(
            @Nullable String contributorId
    ) {
        return contributors.findTarget(contributorId);
    }

    /** Finds a hotswap contributor provider for internal HUD composition. */
    @Nonnull
    public Optional<CommandHotswapHudContributorProvider> findHotswapContributor(
            @Nullable String contributorId
    ) {
        return contributors.findHotswap(contributorId);
    }

    /** Resolves a target contributor with its exact descriptor and generation. */
    @Nonnull
    public Optional<CommandHudContributorRegistry.ResolvedTargetContributor>
    resolveTargetContributor(@Nullable String contributorId) {
        return contributors.resolveTarget(contributorId);
    }

    /** Resolves a hotswap contributor with its exact descriptor and generation. */
    @Nonnull
    public Optional<CommandHudContributorRegistry.ResolvedHotswapContributor>
    resolveHotswapContributor(@Nullable String contributorId) {
        return contributors.resolveHotswap(contributorId);
    }

    @Nonnull
    public CommandHudRendererRegistry rendererRegistry() {
        return renderers;
    }

    @Nonnull
    public CommandHudContributorRegistry contributorRegistry() {
        return contributors;
    }

    @Nonnull
    public Set<CommandHudRendererId> targetRendererIds() {
        return renderers.targetIds();
    }

    @Nonnull
    public Set<CommandHudRendererId> hotswapRendererIds() {
        return renderers.hotswapIds();
    }

    @Nonnull
    public Set<CommandHudContributorId> targetContributorIds() {
        return contributors.targetIds();
    }

    @Nonnull
    public Set<CommandHudContributorId> hotswapContributorIds() {
        return contributors.hotswapIds();
    }

    /** Returns a detached snapshot of all live registration generations. */
    @Override
    @Nonnull
    public CommandHudDiagnostics diagnostics() {
        CommandHudDiagnostics runtime = diagnostics.snapshot();
        List<CommandHudDiagnostics.RendererRegistration> targetRendererValues =
                renderers.targetIds().stream()
                        .map(id -> renderers.resolveTarget(id.value()).orElse(null))
                        .filter(Objects::nonNull)
                        .map(value -> new CommandHudDiagnostics.RendererRegistration(
                                value.id().value(), value.generation()))
                        .sorted(Comparator.comparing(
                                CommandHudDiagnostics.RendererRegistration::rendererId))
                        .toList();
        List<CommandHudDiagnostics.RendererRegistration> hotswapRendererValues =
                renderers.hotswapIds().stream()
                        .map(id -> renderers.resolveHotswap(id.value()).orElse(null))
                        .filter(Objects::nonNull)
                        .map(value -> new CommandHudDiagnostics.RendererRegistration(
                                value.id().value(), value.generation()))
                        .sorted(Comparator.comparing(
                                CommandHudDiagnostics.RendererRegistration::rendererId))
                        .toList();
        List<CommandHudDiagnostics.ContributorRegistration> targetContributorValues =
                contributors.targetIds().stream()
                        .map(id -> contributors.resolveTarget(id.value()).orElse(null))
                        .filter(Objects::nonNull)
                        .map(value -> new CommandHudDiagnostics.ContributorRegistration(
                                value.id().value(), value.generation()))
                        .sorted(Comparator.comparing(
                                CommandHudDiagnostics.ContributorRegistration::contributorId))
                        .toList();
        List<CommandHudDiagnostics.ContributorRegistration> hotswapContributorValues =
                contributors.hotswapIds().stream()
                        .map(id -> contributors.resolveHotswap(id.value()).orElse(null))
                        .filter(Objects::nonNull)
                        .map(value -> new CommandHudDiagnostics.ContributorRegistration(
                                value.id().value(), value.generation()))
                        .sorted(Comparator.comparing(
                                CommandHudDiagnostics.ContributorRegistration::contributorId))
                        .toList();
        return new CommandHudDiagnostics(
                targetRendererValues,
                hotswapRendererValues,
                targetContributorValues,
                hotswapContributorValues,
                runtime.sessions(),
                runtime.latestFailureReason(),
                runtime.slowCompositionCount(),
                runtime.slowWarningCount());
    }

    /** Returns the shared internal diagnostics service for HUD composition. */
    @Nonnull
    public CommandHudDiagnosticsService diagnosticsService() {
        return diagnostics;
    }

    @Override
    public synchronized void close() {
        renderers.close();
        contributors.close();
        diagnostics.close();
    }
}

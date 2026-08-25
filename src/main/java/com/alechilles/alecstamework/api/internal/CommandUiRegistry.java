package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandui.CommandUiApi;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorDescriptor;
import com.alechilles.alecstamework.api.commandui.CommandUiDiagnostics;
import com.alechilles.alecstamework.api.commandui.CommandUiRegistrationResult;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererDescriptor;
import com.alechilles.alecstamework.api.commandui.CommandUiRegistration;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.items.CommandUiDiagnosticsService;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Small public facade over the independent renderer and contributor registries. */
public final class CommandUiRegistry implements CommandUiApi, AutoCloseable {
    private final CommandUiRendererRegistry renderers;
    private final CommandUiContributorRegistry contributors;
    private final CommandUiDiagnosticsService diagnostics =
            new CommandUiDiagnosticsService();

    public CommandUiRegistry() {
        this(new CommandUiRendererRegistry(), new CommandUiContributorRegistry());
    }

    public CommandUiRegistry(
            @Nonnull CommandUiRendererRegistry renderers,
            @Nonnull CommandUiContributorRegistry contributors
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
    public CommandUiRegistrationResult registerRenderer(
            @Nullable String rendererId,
            @Nullable CommandUiRendererProvider provider
    ) {
        return registerRenderer(rendererId,
                CommandUiRendererDescriptor.unrestricted(), provider);
    }

    @Override
    @Nonnull
    public CommandUiRegistrationResult registerRenderer(
            @Nullable String rendererId,
            @Nullable CommandUiRendererDescriptor descriptor,
            @Nullable CommandUiRendererProvider provider
    ) {
        return renderers.register(rendererId, descriptor, provider);
    }

    @Override
    @Nonnull
    public CommandUiRegistrationResult registerContributor(
            @Nullable String contributorId,
            @Nullable CommandUiContributorProvider provider
    ) {
        return registerContributor(contributorId,
                CommandUiContributorDescriptor.unrestricted(), provider);
    }

    @Override
    @Nonnull
    public CommandUiRegistrationResult registerContributor(
            @Nullable String contributorId,
            @Nullable CommandUiContributorDescriptor descriptor,
            @Nullable CommandUiContributorProvider provider
    ) {
        return contributors.register(contributorId, descriptor, provider);
    }

    /** Typed renderer lookup used by the Tamework runtime. */
    @Nonnull
    public Optional<CommandUiRendererProvider> findRenderer(@Nullable String rendererId) {
        return renderers.find(rendererId);
    }

    /** Typed contributor lookup used by the Tamework runtime. */
    @Nonnull
    public Optional<CommandUiContributorProvider> findContributor(@Nullable String contributorId) {
        return contributors.find(contributorId);
    }

    @Nonnull
    public Optional<CommandUiRendererRegistry.ResolvedRenderer> resolveRenderer(
            @Nullable String rendererId) {
        return renderers.resolve(rendererId);
    }

    @Nonnull
    public Optional<CommandUiContributorRegistry.ResolvedContributor> resolveContributor(
            @Nullable String contributorId) {
        return contributors.resolve(contributorId);
    }

    @Nonnull
    public CommandUiRendererRegistry rendererRegistry() {
        return renderers;
    }

    @Nonnull
    public CommandUiContributorRegistry contributorRegistry() {
        return contributors;
    }

    @Nonnull
    public Set<CommandUiRendererId> rendererIds() {
        return renderers.listIds();
    }

    @Nonnull
    public Set<CommandUiContributorId> contributorIds() {
        return contributors.listIds();
    }

    /** Returns a detached snapshot of live registrations and session state. */
    @Override
    @Nonnull
    public CommandUiDiagnostics diagnostics() {
        CommandUiDiagnostics runtime = diagnostics.snapshot();
        List<CommandUiDiagnostics.RendererRegistration> rendererValues =
                renderers.listIds().stream()
                        .map(id -> renderers.resolve(id.value()).orElse(null))
                        .filter(Objects::nonNull)
                        .map(value -> new CommandUiDiagnostics.RendererRegistration(
                                value.id().value(), value.generation()))
                        .sorted(Comparator.comparing(
                                CommandUiDiagnostics.RendererRegistration::rendererId))
                        .toList();
        List<CommandUiDiagnostics.ContributorRegistration> contributorValues =
                contributors.listIds().stream()
                        .map(id -> contributors.resolve(id.value()).orElse(null))
                        .filter(Objects::nonNull)
                        .map(value -> new CommandUiDiagnostics.ContributorRegistration(
                                value.id().value(), value.generation()))
                        .sorted(Comparator.comparing(
                                CommandUiDiagnostics.ContributorRegistration::contributorId))
                        .toList();
        return new CommandUiDiagnostics(rendererValues, contributorValues,
                runtime.sessions(), runtime.latestFailureReason(),
                runtime.slowCompositionCount(), runtime.slowWarningCount());
    }

    /** Returns the shared internal diagnostics service for page composition. */
    @Nonnull
    public CommandUiDiagnosticsService diagnosticsService() {
        return diagnostics;
    }

    @Override
    public synchronized void close() {
        renderers.close();
        contributors.close();
        diagnostics.close();
    }
}

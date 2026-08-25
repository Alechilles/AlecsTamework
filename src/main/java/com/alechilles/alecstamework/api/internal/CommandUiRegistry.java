package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandui.CommandUiApi;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorDescriptor;
import com.alechilles.alecstamework.api.commandui.CommandUiProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiRegistrationResult;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererDescriptor;
import com.alechilles.alecstamework.api.commandui.CommandUiRegistration;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Small public facade over the independent renderer and contributor registries. */
public final class CommandUiRegistry implements CommandUiApi, AutoCloseable {
    private final CommandUiRendererRegistry renderers;
    private final CommandUiContributorRegistry contributors;
    @Nullable
    private final CommandUiProviderRegistry legacyProviders;

    public CommandUiRegistry() {
        this(new CommandUiRendererRegistry(), new CommandUiContributorRegistry());
    }

    public CommandUiRegistry(
            @Nonnull CommandUiRendererRegistry renderers,
            @Nonnull CommandUiContributorRegistry contributors
    ) {
        this(renderers, contributors, new CommandUiProviderRegistry());
    }

    private CommandUiRegistry(
            CommandUiRendererRegistry renderers,
            CommandUiContributorRegistry contributors,
            @Nullable CommandUiProviderRegistry legacyProviders
    ) {
        this.renderers = Objects.requireNonNull(renderers, "renderers");
        this.contributors = Objects.requireNonNull(contributors, "contributors");
        this.legacyProviders = legacyProviders;
    }

    @Override
    public boolean available() {
        return renderers.available() && contributors.available()
                && (legacyProviders == null || legacyProviders.available());
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

    /**
     * Deprecated provider registration is retained only for old source users.
     * Active command-item selection uses renderer registration above.
     */
    @Override
    @Nonnull
    @Deprecated
    public com.alechilles.alecstamework.api.commandui.CommandUiProviderRegistrationResult register(
            @Nullable String providerId,
            @Nullable CommandUiProvider provider
    ) {
        return legacyProviders == null
                ? com.alechilles.alecstamework.api.commandui.CommandUiProviderRegistrationResult
                .unavailable(providerId)
                : legacyProviders.register(providerId, provider);
    }

    @Override
    @Nonnull
    @Deprecated
    public Optional<CommandUiProvider> find(@Nullable String providerId) {
        return legacyProviders == null
                ? Optional.empty() : legacyProviders.find(providerId);
    }

    @Override
    @Nonnull
    public Set<com.alechilles.alecstamework.api.commandui.CommandUiProviderId> listProviderIds() {
        return legacyProviders == null ? Set.of() : legacyProviders.listProviderIds();
    }

    @Override
    public synchronized void close() {
        renderers.close();
        contributors.close();
        if (legacyProviders != null) legacyProviders.close();
    }
}

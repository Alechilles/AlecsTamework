package com.alechilles.alecstamework.api.commandui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Capability-gated registration surface for command-item UI renderers and contributors. */
public interface CommandUiApi {
    /**
     * Returns whether renderer and contributor registration is active for this API instance.
     */
    boolean available();

    /**
     * Registers one custom renderer under a normalized namespaced identifier.
     *
     * <p>Implementations that do not expose the renderer registry return an
     * unavailable result. This default keeps replacement API adapters
     * fail-closed while they are being upgraded.</p>
     */
    @Nonnull
    default CommandUiRegistrationResult registerRenderer(
            @Nullable String rendererId,
            @Nullable CommandUiRendererProvider provider
    ) {
        return CommandUiRegistrationResult.unavailable(rendererId);
    }

    /**
     * Registers one renderer with immutable presentation capabilities.
     *
     * <p>The default delegates to the source-compatible overload. Older API
     * implementations therefore continue to register their provider while
     * newer implementations can retain the descriptor with its generation.</p>
     */
    @Nonnull
    default CommandUiRegistrationResult registerRenderer(
            @Nullable String rendererId,
            @Nullable CommandUiRendererDescriptor descriptor,
            @Nullable CommandUiRendererProvider provider
    ) {
        return registerRenderer(rendererId, provider);
    }

    /** Convenience overload for callers that already parsed an identifier. */
    @Nonnull
    default CommandUiRegistrationResult registerRenderer(
            @Nullable CommandUiRendererId rendererId,
            @Nullable CommandUiRendererProvider provider
    ) {
        return registerRenderer(rendererId == null ? null : rendererId.value(), provider);
    }

    /** Convenience overload for a parsed renderer ID and descriptor. */
    @Nonnull
    default CommandUiRegistrationResult registerRenderer(
            @Nullable CommandUiRendererId rendererId,
            @Nullable CommandUiRendererDescriptor descriptor,
            @Nullable CommandUiRendererProvider provider
    ) {
        return registerRenderer(rendererId == null ? null : rendererId.value(),
                descriptor, provider);
    }

    /**
     * Registers one session contributor under a normalized namespaced
     * identifier.
     */
    @Nonnull
    default CommandUiRegistrationResult registerContributor(
            @Nullable String contributorId,
            @Nullable CommandUiContributorProvider provider
    ) {
        return CommandUiRegistrationResult.unavailable(contributorId);
    }

    /**
     * Registers one contributor with immutable presentation capabilities.
     *
     * <p>The default delegates to the source-compatible overload so older API
     * implementations remain source and behavior compatible.</p>
     */
    @Nonnull
    default CommandUiRegistrationResult registerContributor(
            @Nullable String contributorId,
            @Nullable CommandUiContributorDescriptor descriptor,
            @Nullable CommandUiContributorProvider provider
    ) {
        return registerContributor(contributorId, provider);
    }

    /** Convenience overload for callers that already parsed an identifier. */
    @Nonnull
    default CommandUiRegistrationResult registerContributor(
            @Nullable CommandUiContributorId contributorId,
            @Nullable CommandUiContributorProvider provider
    ) {
        return registerContributor(contributorId == null ? null : contributorId.value(), provider);
    }

    /** Convenience overload for a parsed contributor ID and descriptor. */
    @Nonnull
    default CommandUiRegistrationResult registerContributor(
            @Nullable CommandUiContributorId contributorId,
            @Nullable CommandUiContributorDescriptor descriptor,
            @Nullable CommandUiContributorProvider provider
    ) {
        return registerContributor(contributorId == null ? null : contributorId.value(),
                descriptor, provider);
    }

    /**
     * Returns a value-only diagnostics snapshot.
     *
     * <p>The default is an empty, fail-closed snapshot so older and degraded
     * API implementations remain source compatible.</p>
     */
    @Nonnull
    default CommandUiDiagnostics diagnostics() {
        return CommandUiDiagnostics.empty();
    }

    /** Returns the stable fail-closed adapter for legacy and degraded APIs. */
    @Nonnull
    static CommandUiApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Holder avoids allocating an unavailable adapter for every API call. */
    final class UnavailableHolder {
        private static final CommandUiApi INSTANCE = new CommandUiApi() {
            @Override
            public boolean available() {
                return false;
            }
        };

        private UnavailableHolder() {
        }
    }
}

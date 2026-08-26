package com.alechilles.alecstamework.api.commandhud;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Capability-gated registration surface for command HUD renderers and contributors. */
public interface CommandHudApi {
    /** Returns whether all command HUD registration routes are active. */
    boolean available();

    /** Registers a target-HUD renderer using an unrestricted descriptor. */
    @Nonnull
    default CommandHudRegistrationResult registerTargetRenderer(
            @Nullable String rendererId,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        return CommandHudRegistrationResult.unavailable(rendererId);
    }

    /** Registers a target-HUD renderer with immutable capability metadata. */
    @Nonnull
    default CommandHudRegistrationResult registerTargetRenderer(
            @Nullable String rendererId,
            @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        return registerTargetRenderer(rendererId, provider);
    }

    /** Registers a target renderer for a parsed renderer identifier. */
    @Nonnull
    default CommandHudRegistrationResult registerTargetRenderer(
            @Nullable CommandHudRendererId rendererId,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        return registerTargetRenderer(rendererId == null ? null : rendererId.value(), provider);
    }

    /** Registers a target renderer for a parsed identifier and descriptor. */
    @Nonnull
    default CommandHudRegistrationResult registerTargetRenderer(
            @Nullable CommandHudRendererId rendererId,
            @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandTargetHudRendererProvider provider
    ) {
        return registerTargetRenderer(rendererId == null ? null : rendererId.value(),
                descriptor, provider);
    }

    /** Registers a hotswap-HUD renderer using an unrestricted descriptor. */
    @Nonnull
    default CommandHudRegistrationResult registerHotswapRenderer(
            @Nullable String rendererId,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        return CommandHudRegistrationResult.unavailable(rendererId);
    }

    /** Registers a hotswap-HUD renderer with immutable capability metadata. */
    @Nonnull
    default CommandHudRegistrationResult registerHotswapRenderer(
            @Nullable String rendererId,
            @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        return registerHotswapRenderer(rendererId, provider);
    }

    /** Registers a hotswap renderer for a parsed renderer identifier. */
    @Nonnull
    default CommandHudRegistrationResult registerHotswapRenderer(
            @Nullable CommandHudRendererId rendererId,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        return registerHotswapRenderer(rendererId == null ? null : rendererId.value(), provider);
    }

    /** Registers a hotswap renderer for a parsed identifier and descriptor. */
    @Nonnull
    default CommandHudRegistrationResult registerHotswapRenderer(
            @Nullable CommandHudRendererId rendererId,
            @Nullable CommandHudRendererDescriptor descriptor,
            @Nullable CommandHotswapHudRendererProvider provider
    ) {
        return registerHotswapRenderer(rendererId == null ? null : rendererId.value(),
                descriptor, provider);
    }

    /** Registers a target-HUD contributor using an unrestricted descriptor. */
    @Nonnull
    default CommandHudRegistrationResult registerTargetContributor(
            @Nullable String contributorId,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        return CommandHudRegistrationResult.unavailable(contributorId);
    }

    /** Registers a target contributor with immutable capability metadata. */
    @Nonnull
    default CommandHudRegistrationResult registerTargetContributor(
            @Nullable String contributorId,
            @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        return registerTargetContributor(contributorId, provider);
    }

    /** Registers a target contributor for a parsed contributor identifier. */
    @Nonnull
    default CommandHudRegistrationResult registerTargetContributor(
            @Nullable CommandHudContributorId contributorId,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        return registerTargetContributor(
                contributorId == null ? null : contributorId.value(), provider);
    }

    /** Registers a target contributor for a parsed identifier and descriptor. */
    @Nonnull
    default CommandHudRegistrationResult registerTargetContributor(
            @Nullable CommandHudContributorId contributorId,
            @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandTargetHudContributorProvider provider
    ) {
        return registerTargetContributor(
                contributorId == null ? null : contributorId.value(), descriptor, provider);
    }

    /** Registers a hotswap-HUD contributor using an unrestricted descriptor. */
    @Nonnull
    default CommandHudRegistrationResult registerHotswapContributor(
            @Nullable String contributorId,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        return CommandHudRegistrationResult.unavailable(contributorId);
    }

    /** Registers a hotswap contributor with immutable capability metadata. */
    @Nonnull
    default CommandHudRegistrationResult registerHotswapContributor(
            @Nullable String contributorId,
            @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        return registerHotswapContributor(contributorId, provider);
    }

    /** Registers a hotswap contributor for a parsed contributor identifier. */
    @Nonnull
    default CommandHudRegistrationResult registerHotswapContributor(
            @Nullable CommandHudContributorId contributorId,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        return registerHotswapContributor(
                contributorId == null ? null : contributorId.value(), provider);
    }

    /** Registers a hotswap contributor for a parsed identifier and descriptor. */
    @Nonnull
    default CommandHudRegistrationResult registerHotswapContributor(
            @Nullable CommandHudContributorId contributorId,
            @Nullable CommandHudContributorDescriptor descriptor,
            @Nullable CommandHotswapHudContributorProvider provider
    ) {
        return registerHotswapContributor(
                contributorId == null ? null : contributorId.value(), descriptor, provider);
    }

    /** Returns a detached, value-only diagnostics snapshot. */
    @Nonnull
    default CommandHudDiagnostics diagnostics() {
        return CommandHudDiagnostics.empty();
    }

    /** Returns the stable fail-closed adapter for legacy and degraded APIs. */
    @Nonnull
    static CommandHudApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Holder avoids allocating an unavailable adapter for each API call. */
    final class UnavailableHolder {
        private static final CommandHudApi INSTANCE = new CommandHudApi() {
            @Override
            public boolean available() {
                return false;
            }
        };

        private UnavailableHolder() {
        }
    }
}

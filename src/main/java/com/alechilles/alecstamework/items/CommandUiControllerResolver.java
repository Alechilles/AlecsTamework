package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRendererRegistry;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects a custom controller before the host fixes its event codec. */
final class CommandUiControllerResolver {
    @Nullable
    private final CommandUiProviderRegistry legacyRegistry;
    @Nullable
    private final CommandUiCompositionResolver compositionResolver;

    CommandUiControllerResolver(@Nonnull CommandUiProviderRegistry registry) {
        this.legacyRegistry = Objects.requireNonNull(registry, "registry");
        this.compositionResolver = null;
    }

    CommandUiControllerResolver(
            @Nonnull CommandUiRendererRegistry renderers,
            @Nonnull CommandUiContributorRegistry contributors
    ) {
        this.legacyRegistry = null;
        this.compositionResolver = new CommandUiCompositionResolver(
                renderers, contributors);
    }

    @Nonnull
    Resolved resolve(
            @Nullable String configuredProviderId,
            @Nonnull CommandUiOpenContext context,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(standardFactory, "standardFactory");
        if (compositionResolver != null) {
            return fromComposition(compositionResolver.resolve(
                    configuredProviderId, List.of(), context, standardFactory));
        }
        return resolveLegacy(configuredProviderId, context, standardFactory);
    }

    @Nonnull
    Resolved resolve(
            @Nullable CommandUiRendererId rendererId,
            @Nonnull List<CommandUiContributorRequirement> requirements,
            @Nonnull CommandUiOpenContext context,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory
    ) {
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(standardFactory, "standardFactory");
        if (compositionResolver == null) {
            return resolveLegacy(rendererId == null ? null : rendererId.value(),
                    context, standardFactory);
        }
        return fromComposition(compositionResolver.resolve(rendererId,
                requirements, context, standardFactory));
    }

    @Nonnull
    private Resolved resolveLegacy(
            @Nullable String configuredProviderId,
            @Nonnull CommandUiOpenContext context,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory
    ) {
        var provider = legacyRegistry == null
                ? java.util.Optional.<CommandUiProviderRegistry.ResolvedProvider>empty()
                : legacyRegistry.resolve(configuredProviderId);
        if (provider.isEmpty()) return standard(standardFactory);

        CommandUiPageController<?> controller = null;
        try {
            var resolved = provider.orElseThrow();
            controller = resolved.provider().create(context);
            if (controller == null || controller.eventCodec() == null) {
                close(controller);
                return standard(standardFactory);
            }
            return new Resolved(controller, resolved.providerId(),
                    toRendererId(resolved.providerId()), resolved.generation(),
                    resolved.generation(), true, List.of());
        } catch (RuntimeException | LinkageError failure) {
            close(controller);
            return standard(standardFactory);
        }
    }

    @Nonnull
    private static Resolved fromComposition(
            @Nonnull CommandUiCompositionResolver.Resolved resolved
    ) {
        if (!resolved.custom()) return standardResolved(resolved.controller());
        CommandUiRendererId rendererId = resolved.rendererId();
        CommandUiProviderId providerId = rendererId == null
                ? null : CommandUiProviderId.tryParse(rendererId.value()).orElse(null);
        return new Resolved(resolved.controller(), providerId, rendererId,
                resolved.rendererGeneration(), resolved.rendererGeneration(),
                true, resolved.contributors());
    }

    @Nonnull
    private static Resolved standard(
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory
    ) {
        CommandUiPageController<?> controller = Objects.requireNonNull(
                standardFactory.get(), "standard controller");
        Objects.requireNonNull(controller.eventCodec(),
                "standard controller event codec");
        return standardResolved(controller);
    }

    @Nonnull
    private static Resolved standardResolved(
            @Nonnull CommandUiPageController<?> controller
    ) {
        return new Resolved(controller, null, null, 0L, 0L, false, List.of());
    }

    @Nullable
    private static CommandUiRendererId toRendererId(
            @Nonnull CommandUiProviderId providerId
    ) {
        return CommandUiRendererId.tryParse(providerId.value()).orElse(null);
    }

    private static void close(@Nullable CommandUiPageController<?> controller) {
        if (controller == null) return;
        try {
            controller.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Startup fallback must remain available.
        }
    }

    record Resolved(
            @Nonnull CommandUiPageController<?> controller,
            @Nullable CommandUiProviderId providerId,
            @Nullable CommandUiRendererId rendererId,
            long providerGeneration,
            long rendererGeneration,
            boolean custom,
            @Nonnull List<CommandUiCompositionSession.Binding> contributors
    ) {
        Resolved {
            Objects.requireNonNull(controller, "controller");
            contributors = List.copyOf(Objects.requireNonNull(
                    contributors, "contributors"));
        }

        @Nonnull
        List<CommandUiCompositionSession.Binding> bindings() {
            return contributors;
        }
    }
}

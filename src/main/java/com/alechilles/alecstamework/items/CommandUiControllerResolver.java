package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects a custom controller before the host fixes its event codec. */
final class CommandUiControllerResolver {
    private final CommandUiProviderRegistry registry;

    CommandUiControllerResolver(@Nonnull CommandUiProviderRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Nonnull
    Resolved resolve(
            @Nullable String configuredProviderId,
            @Nonnull CommandUiOpenContext context,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(standardFactory, "standardFactory");
        var provider = registry.resolve(configuredProviderId);
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
                    resolved.generation(), true);
        } catch (RuntimeException | LinkageError failure) {
            close(controller);
            return standard(standardFactory);
        }
    }

    private static Resolved standard(
            Supplier<CommandUiPageController<?>> standardFactory) {
        CommandUiPageController<?> controller = Objects.requireNonNull(
                standardFactory.get(), "standard controller");
        Objects.requireNonNull(controller.eventCodec(),
                "standard controller event codec");
        return new Resolved(controller, null, 0L, false);
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
            long providerGeneration,
            boolean custom
    ) {
    }
}

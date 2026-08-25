package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorProvider;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererProvider;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRendererRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves one renderer and its ordered contributor registrations. */
final class CommandUiCompositionResolver {
    private final CommandUiRendererRegistry renderers;
    private final CommandUiContributorRegistry contributors;

    CommandUiCompositionResolver(
            @Nonnull CommandUiRendererRegistry renderers,
            @Nonnull CommandUiContributorRegistry contributors
    ) {
        this.renderers = Objects.requireNonNull(renderers, "renderers");
        this.contributors = Objects.requireNonNull(contributors, "contributors");
    }

    /**
     * Resolves a custom presentation or returns a standard controller.
     * Optional contributors that are not registered are omitted. A missing
     * renderer or required contributor uses the standard controller.
     */
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
        if (rendererId == null) return standard(standardFactory);

        Optional<CommandUiRendererRegistry.ResolvedRenderer> renderer =
                renderers.resolve(rendererId.value());
        if (renderer.isEmpty()) return standard(standardFactory);

        CommandUiRendererRegistry.ResolvedRenderer resolved = renderer.orElseThrow();
        List<CommandUiCompositionSession.Binding> bindings = new ArrayList<>();
        Map<CommandUiContributorId, CommandUiContribution.Status> statuses =
                new LinkedHashMap<>();
        for (CommandUiContributorRequirement requirement : requirements) {
            if (requirement == null || requirement.id() == null) {
                continue;
            }
            Optional<CommandUiContributorRegistry.ResolvedContributor>
                    contributor = contributors.resolve(requirement.id().value());
            if (contributor.isEmpty()) {
                if (requirement.required()) {
                    return standard(standardFactory);
                }
                statuses.put(requirement.id(),
                        CommandUiContribution.Status.OPTIONAL_UNAVAILABLE);
                continue;
            }
            CommandUiContributorRegistry.ResolvedContributor value =
                    contributor.orElseThrow();
            if (!resolved.descriptor().supports(value.id(), value.descriptor())) {
                if (requirement.required()) {
                    return standard(standardFactory);
                }
                statuses.put(value.id(),
                        CommandUiContribution.Status.UNSUPPORTED_BY_RENDERER);
                continue;
            }
            bindings.add(new CommandUiCompositionSession.Binding(
                    value.id(), value.generation(), value.provider(),
                    requirement.required(), contributors));
        }

        CommandUiPageController<?> controller = null;
        try {
            CommandUiRendererProvider provider = resolved.provider();
            controller = provider.create(context);
            if (controller == null || controller.eventCodec() == null) {
                close(controller);
                return standard(standardFactory);
            }
            return new Resolved(
                    controller,
                    resolved.id(),
                    resolved.generation(),
                    true,
                    List.copyOf(bindings),
                    statuses);
        } catch (RuntimeException | LinkageError failure) {
            close(controller);
            return standard(standardFactory);
        }
    }

    /** Convenience overload for configuration accessors. */
    @Nonnull
    Resolved resolve(
            @Nullable String rendererId,
            @Nonnull List<CommandUiContributorRequirement> requirements,
            @Nonnull CommandUiOpenContext context,
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory
    ) {
        return resolve(CommandUiRendererId.tryParse(rendererId).orElse(null),
                requirements, context, standardFactory);
    }

    @Nonnull
    private static Resolved standard(
            @Nonnull Supplier<CommandUiPageController<?>> standardFactory
    ) {
        CommandUiPageController<?> controller = Objects.requireNonNull(
                standardFactory.get(), "standard controller");
        Objects.requireNonNull(controller.eventCodec(),
                "standard controller event codec");
        return new Resolved(controller, null, 0L, false, List.of());
    }

    private static void close(@Nullable CommandUiPageController<?> controller) {
        if (controller == null) return;
        try {
            controller.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Fallback remains available when a custom controller cleanup fails.
        }
    }

    /** Complete resolution used by the page coordinator. */
    record Resolved(
            @Nonnull CommandUiPageController<?> controller,
            @Nullable CommandUiRendererId rendererId,
            long rendererGeneration,
            boolean custom,
            @Nonnull List<CommandUiCompositionSession.Binding> contributors,
            @Nonnull Map<CommandUiContributorId,
                    CommandUiContribution.Status> statuses
    ) {
        Resolved {
            Objects.requireNonNull(controller, "controller");
            if (rendererGeneration < 0L) {
                throw new IllegalArgumentException(
                        "Renderer generation cannot be negative.");
            }
            contributors = List.copyOf(Objects.requireNonNull(
                    contributors, "contributors"));
            statuses = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            statuses, "statuses")));
            if (!custom && (rendererId != null || rendererGeneration != 0L
                    || !contributors.isEmpty() || !statuses.isEmpty())) {
                throw new IllegalArgumentException(
                        "Standard resolution cannot carry custom state.");
            }
        }

        Resolved(
                @Nonnull CommandUiPageController<?> controller,
                @Nullable CommandUiRendererId rendererId,
                long rendererGeneration,
                boolean custom,
                @Nonnull List<CommandUiCompositionSession.Binding> contributors
        ) {
            this(controller, rendererId, rendererGeneration, custom,
                    contributors, Map.of());
        }

        /** Alias for callers that describe the ordered list as bindings. */
        @Nonnull
        List<CommandUiCompositionSession.Binding> bindings() {
            return contributors;
        }

        /** Returns optional contributor compatibility statuses recorded at open. */
        @Nonnull
        Map<CommandUiContributorId, CommandUiContribution.Status>
                contributorStatuses() {
            return statuses;
        }
    }
}

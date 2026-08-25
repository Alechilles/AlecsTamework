package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPageController;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.alechilles.alecstamework.api.internal.CommandUiContributorRegistry;
import com.alechilles.alecstamework.api.internal.CommandUiRendererRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects a custom controller before the host fixes its event codec. */
final class CommandUiControllerResolver {
    private final CommandUiCompositionResolver compositionResolver;

    CommandUiControllerResolver(
            @Nonnull CommandUiRendererRegistry renderers,
            @Nonnull CommandUiContributorRegistry contributors
    ) {
        this.compositionResolver = new CommandUiCompositionResolver(
                renderers, contributors);
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
        return fromComposition(compositionResolver.resolve(rendererId,
                requirements, context, standardFactory));
    }

    @Nonnull
    private static Resolved fromComposition(
            @Nonnull CommandUiCompositionResolver.Resolved resolved
    ) {
        if (!resolved.custom()) return standardResolved(resolved.controller());
        return new Resolved(resolved.controller(), resolved.rendererId(),
                resolved.rendererGeneration(),
                true, resolved.contributors(),
                resolved.contributorStatuses());
    }

    @Nonnull
    private static Resolved standardResolved(
            @Nonnull CommandUiPageController<?> controller
    ) {
        return new Resolved(controller, null, 0L, false, List.of(), Map.of());
    }

    record Resolved(
            @Nonnull CommandUiPageController<?> controller,
            @Nullable CommandUiRendererId rendererId,
            long rendererGeneration,
            boolean custom,
            @Nonnull List<CommandUiCompositionSession.Binding> contributors,
            @Nonnull Map<CommandUiContributorId, CommandUiContribution.Status>
                    contributorStatuses
    ) {
        Resolved {
            Objects.requireNonNull(controller, "controller");
            contributors = List.copyOf(Objects.requireNonNull(
                    contributors, "contributors"));
            contributorStatuses = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(Objects.requireNonNull(
                            contributorStatuses, "contributorStatuses")));
        }

        @Nonnull
        List<CommandUiCompositionSession.Binding> bindings() {
            return contributors;
        }
    }
}

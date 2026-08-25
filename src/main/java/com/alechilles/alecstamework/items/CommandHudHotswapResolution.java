package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererId;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudRendererProvider;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable hotswap-HUD renderer and contributor resolution. */
final class CommandHudHotswapResolution {
    @Nullable
    private final CommandHudRendererId rendererId;
    @Nullable
    private final CommandHotswapHudRendererProvider rendererProvider;
    private final long rendererGeneration;
    private final boolean custom;
    @Nonnull
    private final List<CommandHudCompositionBinding<
            com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot>> contributors;
    @Nonnull
    private final Map<CommandHudContributorId, CommandHudContribution> contributions;
    @Nonnull
    private final BooleanSupplier rendererActive;

    CommandHudHotswapResolution(
            @Nullable CommandHudRendererId rendererId,
            @Nullable CommandHotswapHudRendererProvider rendererProvider,
            long rendererGeneration,
            boolean custom,
            @Nonnull List<CommandHudCompositionBinding<
                    com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot>> contributors,
            @Nonnull Map<CommandHudContributorId, CommandHudContribution> contributions,
            @Nonnull BooleanSupplier rendererActive
    ) {
        if (rendererGeneration < 0L) {
            throw new IllegalArgumentException("Renderer generation cannot be negative.");
        }
        if (custom && (rendererId == null || rendererProvider == null)) {
            throw new IllegalArgumentException("Custom hotswap resolution needs a renderer.");
        }
        if (!custom && (rendererId != null || rendererProvider != null
                || rendererGeneration != 0L || !contributors.isEmpty()
                || !Objects.requireNonNull(contributions, "contributions").isEmpty())) {
            throw new IllegalArgumentException("Standard hotswap resolution cannot carry custom state.");
        }
        this.rendererId = rendererId;
        this.rendererProvider = rendererProvider;
        this.rendererGeneration = rendererGeneration;
        this.custom = custom;
        this.contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
        LinkedHashMap<CommandHudContributorId, CommandHudContribution> copied = new LinkedHashMap<>();
        contributions.forEach((id, contribution) -> {
            if (id == null) return;
            CommandHudContribution value = Objects.requireNonNull(contribution, "contribution");
            if (!id.equals(value.contributorId())) {
                throw new IllegalArgumentException("Contribution key must match contributor ID.");
            }
            copied.put(id, value);
        });
        this.contributions = copied.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(copied);
        this.rendererActive = Objects.requireNonNull(rendererActive, "rendererActive");
    }

    static CommandHudHotswapResolution standard() {
        return new CommandHudHotswapResolution(null, null, 0L, false,
                List.of(), Map.of(), () -> false);
    }

    boolean custom() {
        return custom;
    }

    @Nonnull
    com.alechilles.alecstamework.api.commandhud.CommandHudSurface surface() {
        return com.alechilles.alecstamework.api.commandhud.CommandHudSurface.HOTSWAP;
    }

    @Nullable
    CommandHudRendererId rendererId() {
        return rendererId;
    }

    @Nullable
    CommandHotswapHudRendererProvider rendererProvider() {
        return rendererProvider;
    }

    @Nullable
    CommandHotswapHudRendererProvider provider() {
        return rendererProvider;
    }

    long rendererGeneration() {
        return rendererGeneration;
    }

    @Nonnull
    List<CommandHudCompositionBinding<
            com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot>> contributors() {
        return contributors;
    }

    @Nonnull
    List<CommandHudCompositionBinding<
            com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot>> bindings() {
        return contributors;
    }

    @Nonnull
    Map<CommandHudContributorId, CommandHudContribution> contributions() {
        return contributions;
    }

    @Nonnull
    Map<CommandHudContributorId, com.alechilles.alecstamework.api.commandhud.CommandHudContributionStatus>
            contributorStatuses() {
        Map<CommandHudContributorId, com.alechilles.alecstamework.api.commandhud.CommandHudContributionStatus>
                statuses = new LinkedHashMap<>();
        contributions.forEach((id, contribution) -> statuses.put(id, contribution.status()));
        return statuses.isEmpty() ? Map.of() : Map.copyOf(statuses);
    }

    boolean rendererActive() {
        try {
            return custom && rendererActive.getAsBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}

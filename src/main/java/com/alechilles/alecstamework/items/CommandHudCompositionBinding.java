package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudContributorProvider;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandHudContribution;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorCreateContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudDirtyScope;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudContributorProvider;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSessionContributor;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import com.alechilles.alecstamework.api.internal.CommandHudContributorRegistry;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One exact contributor registration selected for a HUD session. */
final class CommandHudCompositionBinding<B> {
    @FunctionalInterface
    interface Factory<B> {
        @Nullable
        CommandHudContributorSession<B> create(
                @Nonnull CommandHudContributorCreateContext context);
    }

    private final CommandHudContributorId id;
    private final long generation;
    private final Factory<B> factory;
    private final boolean required;
    private final BooleanSupplier active;
    @Nullable
    private final CommandHudContributorRegistry registry;
    private final boolean target;

    CommandHudCompositionBinding(
            @Nonnull CommandHudContributorId id,
            long generation,
            @Nonnull Factory<B> factory,
            boolean required,
            @Nonnull BooleanSupplier active
    ) {
        this(id, generation, factory, required, active, null, true);
    }

    private CommandHudCompositionBinding(
            @Nonnull CommandHudContributorId id,
            long generation,
            @Nonnull Factory<B> factory,
            boolean required,
            @Nonnull BooleanSupplier active,
            @Nullable CommandHudContributorRegistry registry,
            boolean target
    ) {
        this.id = Objects.requireNonNull(id, "id");
        if (generation < 0L) {
            throw new IllegalArgumentException("Contributor generation cannot be negative.");
        }
        this.generation = generation;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.required = required;
        this.active = Objects.requireNonNull(active, "active");
        this.registry = registry;
        this.target = target;
    }

    static CommandHudCompositionBinding<CommandTargetHudSnapshot> target(
            @Nonnull CommandHudContributorId id,
            long generation,
            @Nonnull CommandTargetHudContributorProvider provider,
            boolean required,
            @Nonnull CommandHudContributorRegistry registry
    ) {
        Objects.requireNonNull(provider, "provider");
        return new CommandHudCompositionBinding<>(id, generation, context -> {
            CommandTargetHudSessionContributor contributor = provider.create(context);
            if (contributor == null) return null;
            return new CommandHudContributorSession<>() {
                @Override
                public CommandHudContribution compose(
                        CommandTargetHudSnapshot base,
                        CommandHudContribution previous,
                        CommandHudDirtyScope scope
                ) {
                    return contributor.compose(base, previous, scope);
                }

                @Override
                public void close() {
                    contributor.close();
                }
            };
        }, required, () -> registry.isTargetActive(id, generation), registry, true);
    }

    static CommandHudCompositionBinding<CommandHotswapHudSnapshot> hotswap(
            @Nonnull CommandHudContributorId id,
            long generation,
            @Nonnull CommandHotswapHudContributorProvider provider,
            boolean required,
            @Nonnull CommandHudContributorRegistry registry
    ) {
        Objects.requireNonNull(provider, "provider");
        return new CommandHudCompositionBinding<>(id, generation, context -> {
            CommandHotswapHudSessionContributor contributor = provider.create(context);
            if (contributor == null) return null;
            return new CommandHudContributorSession<>() {
                @Override
                public CommandHudContribution compose(
                        CommandHotswapHudSnapshot base,
                        CommandHudContribution previous,
                        CommandHudDirtyScope scope
                ) {
                    return contributor.compose(base, previous, scope);
                }

                @Override
                public void close() {
                    contributor.close();
                }
            };
        }, required, () -> registry.isHotswapActive(id, generation), registry, false);
    }

    @Nonnull CommandHudContributorId id() { return id; }
    long generation() { return generation; }
    @Nonnull Factory<B> factory() { return factory; }
    boolean required() { return required; }
    @Nonnull BooleanSupplier active() { return active; }
    @Nullable CommandHudContributorRegistry registry() { return registry; }
    boolean target() { return target; }
}

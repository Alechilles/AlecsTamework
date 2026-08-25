package com.alechilles.alecstamework.api.commandhud;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Composite detached hotswap snapshot and namespaced contributor data. */
public final class CommandHotswapHudView {
    @Nonnull
    private final CommandHotswapHudSnapshot snapshot;
    @Nonnull
    private final Map<CommandHudContributorId, CommandHudContribution> contributions;

    /** Creates a view without contributor data. */
    public CommandHotswapHudView(@Nonnull CommandHotswapHudSnapshot snapshot) {
        this(snapshot, Map.of());
    }

    /** Creates a view with an immutable contribution map. */
    public CommandHotswapHudView(
            @Nonnull CommandHotswapHudSnapshot snapshot,
            @Nullable Map<CommandHudContributorId, CommandHudContribution> contributions
    ) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.contributions = copyContributions(contributions);
    }

    @Nonnull
    public CommandHotswapHudSnapshot snapshot() {
        return snapshot;
    }

    @Nonnull
    public CommandHotswapHudSnapshot baseSnapshot() {
        return snapshot;
    }

    @Nonnull
    public Map<CommandHudContributorId, CommandHudContribution> contributions() {
        return contributions;
    }

    @Nullable
    public CommandHudContribution contribution(@Nullable CommandHudContributorId id) {
        return id == null ? null : contributions.get(id);
    }

    @Nonnull
    private static Map<CommandHudContributorId, CommandHudContribution> copyContributions(
            @Nullable Map<CommandHudContributorId, CommandHudContribution> source
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<CommandHudContributorId, CommandHudContribution> copy =
                new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) return;
            CommandHudContribution contribution =
                    Objects.requireNonNull(value, "contribution");
            if (!key.equals(contribution.contributorId())) {
                throw new IllegalArgumentException(
                        "Contribution map key must match the contribution ID.");
            }
            copy.put(key, contribution);
        });
        return Map.copyOf(copy);
    }
}

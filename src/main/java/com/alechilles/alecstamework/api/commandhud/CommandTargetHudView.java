package com.alechilles.alecstamework.api.commandhud;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Composite detached target snapshot and namespaced contributor data. */
public final class CommandTargetHudView {
    @Nonnull
    private final CommandTargetHudSnapshot snapshot;
    @Nonnull
    private final Map<CommandHudContributorId, CommandHudContribution> contributions;

    /** Creates a view without contributor data. */
    public CommandTargetHudView(@Nonnull CommandTargetHudSnapshot snapshot) {
        this(snapshot, Map.of());
    }

    /** Creates a view with an immutable contribution map. */
    public CommandTargetHudView(
            @Nonnull CommandTargetHudSnapshot snapshot,
            @Nullable Map<CommandHudContributorId, CommandHudContribution> contributions
    ) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.contributions = copyContributions(contributions);
    }

    @Nonnull
    public CommandTargetHudSnapshot snapshot() {
        return snapshot;
    }

    /** Alias for callers that distinguish the base snapshot from extensions. */
    @Nonnull
    public CommandTargetHudSnapshot baseSnapshot() {
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

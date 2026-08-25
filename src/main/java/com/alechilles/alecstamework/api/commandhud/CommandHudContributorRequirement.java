package com.alechilles.alecstamework.api.commandhud;

import java.util.Objects;
import javax.annotation.Nonnull;

/** One configured contributor and whether its absence requires fallback. */
public record CommandHudContributorRequirement(
        @Nonnull CommandHudContributorId id,
        boolean required
) {
    public CommandHudContributorRequirement {
        id = Objects.requireNonNull(id, "id");
    }

    /** Convenience constructor for a namespaced configuration value. */
    public CommandHudContributorRequirement(@Nonnull String id, boolean required) {
        this(CommandHudContributorId.of(id), required);
    }
}

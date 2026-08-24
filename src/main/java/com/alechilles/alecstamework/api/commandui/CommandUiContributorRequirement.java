package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;

/** One configured contributor and whether its absence stops custom UI opening. */
public record CommandUiContributorRequirement(
        @Nonnull CommandUiContributorId id,
        boolean required
) {
    public CommandUiContributorRequirement {
        id = Objects.requireNonNull(id, "id");
    }

}

package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, detached capture-item data supplied to a display provider. */
public record CapturedItemDisplayContext(
        @Nonnull String itemId,
        @Nullable String roleId,
        @Nullable ProgressionView.TraitsView traits
) {
    public CapturedItemDisplayContext {
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (traits != null) {
            List<ProgressionView.TraitValueView> values = List.copyOf(traits.values());
            traits = new ProgressionView.TraitsView(traits.configId(), traits.rollSeed(), values);
        }
    }
}

package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record InteractionConfigView(@Nonnull String id,
                                    @Nullable String parentId,
                                    boolean enabled,
                                    int priority,
                                    @Nonnull Set<String> roleIds,
                                    @Nullable Integer interactionCooldownSeconds,
                                    @Nonnull List<InteractionEntryView> interactions) {
    public InteractionConfigView {
        roleIds = Set.copyOf(roleIds);
        interactions = List.copyOf(interactions);
    }

    public record InteractionEntryView(@Nonnull String type,
                                       boolean enabled,
                                       @Nullable Integer cooldownSeconds,
                                       @Nullable String promptHint,
                                       @Nullable Boolean showPrompt,
                                       @Nonnull String detailsJson) {
    }
}


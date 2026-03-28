package com.alechilles.alecstamework.api;

import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record CommandItemConfigView(@Nonnull String id,
                                    @Nullable String parentId,
                                    boolean enabled,
                                    @Nonnull Set<String> itemIds,
                                    @Nonnull String detailsJson) {
    public CommandItemConfigView {
        itemIds = Set.copyOf(itemIds);
    }
}

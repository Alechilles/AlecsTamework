package com.alechilles.alecstamework.api;

import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record RoleScopedConfigView(@Nonnull String id,
                                   @Nullable String parentId,
                                   boolean enabled,
                                   int priority,
                                   @Nonnull Set<String> roleIds,
                                   @Nonnull String detailsJson) {
    public RoleScopedConfigView {
        roleIds = Set.copyOf(roleIds);
    }
}

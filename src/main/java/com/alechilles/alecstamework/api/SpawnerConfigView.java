package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record SpawnerConfigView(@Nonnull String id,
                                @Nullable String parentId,
                                @Nullable String emptyItemId,
                                @Nullable String filledItemId,
                                @Nonnull String detailsJson) {
}

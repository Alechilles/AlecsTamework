package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record NameItemConfigView(@Nonnull String id,
                                 @Nullable String parentId,
                                 @Nullable String itemId,
                                 @Nonnull String detailsJson) {
}

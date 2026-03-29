package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

public record InteractionEffectSpec(@Nullable String id,
                                    @Nullable String param,
                                    List<String> values,
                                    @Nullable String jsonPayload) {
    public InteractionEffectSpec {
        param = normalizeBlank(param);
        jsonPayload = normalizeBlank(jsonPayload);
        values = values == null
                ? List.of()
                : values.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable parameters supplied by one capture-policy requirement declaration. */
public record CaptureRequirementSpec(@Nonnull String id,
                                     @Nullable String param,
                                     @Nonnull List<String> values,
                                     @Nullable String jsonPayload) {
    public CaptureRequirementSpec {
        id = requireId(id);
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

    private static String requireId(String value) {
        String normalized = normalizeBlank(value);
        if (normalized == null) {
            throw new IllegalArgumentException("Capture requirement id is required.");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

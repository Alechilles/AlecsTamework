package com.alechilles.alecstamework.npc.breeding;

import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable, fully resolved child specification captured once when a breeding pair is admitted.
 */
public record PlannedChild(@Nonnull String roleId,
                           @Nullable String adultRoleId,
                           @Nullable String gender,
                           @Nullable String lifecycleFamily,
                           @Nonnull String populationType) {
    public PlannedChild {
        roleId = requireNonBlank(roleId, "roleId");
        adultRoleId = normalizeOptional(adultRoleId);
        gender = normalizeOptional(gender);
        lifecycleFamily = normalizeOptional(lifecycleFamily);
        populationType = requireNonBlank(populationType, "populationType").toLowerCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

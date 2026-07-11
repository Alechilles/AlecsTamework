package com.alechilles.alecstamework.ownership;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable, engine-independent representation of one exact breeding litter plan. */
public record BreedingBirthPlanSnapshot(
        double parentAMultiplier,
        double parentBMultiplier,
        double expectedOffspring,
        int offspringCount,
        @Nonnull List<PlannedChild> children
) {
    public BreedingBirthPlanSnapshot {
        requireFinite(parentAMultiplier, "parentAMultiplier");
        requireFinite(parentBMultiplier, "parentBMultiplier");
        requireFinite(expectedOffspring, "expectedOffspring");
        if (offspringCount < 0) {
            throw new IllegalArgumentException("offspringCount cannot be negative.");
        }
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        Set<String> keys = new HashSet<>();
        for (PlannedChild child : children) {
            Objects.requireNonNull(child, "children cannot contain null");
            if (!keys.add(child.childKey())) {
                throw new IllegalArgumentException("Duplicate planned child key: " + child.childKey());
            }
        }
    }

    /** Exact role, lifecycle selector, inherited owner, and population type for one child. */
    public record PlannedChild(
            @Nonnull String childKey,
            @Nonnull String roleId,
            int roleIndex,
            @Nullable String adultRoleId,
            @Nullable String gender,
            boolean lifecycleFamilyPresent,
            @Nullable String lifecycleFamilyId,
            @Nullable String lifecycleLineId,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            @Nonnull String populationType
    ) {
        public PlannedChild {
            childKey = requireText(childKey, "childKey");
            roleId = requireText(roleId, "roleId");
            adultRoleId = normalizeOptional(adultRoleId);
            gender = normalizeOptional(gender);
            lifecycleFamilyId = normalizeOptional(lifecycleFamilyId);
            lifecycleLineId = normalizeOptional(lifecycleLineId);
            ownerName = normalizeOptional(ownerName);
            populationType = requireText(populationType, "populationType");
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite.");
        }
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank.");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

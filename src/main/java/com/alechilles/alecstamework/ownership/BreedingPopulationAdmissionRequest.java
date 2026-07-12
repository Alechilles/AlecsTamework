package com.alechilles.alecstamework.ownership;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable exact-child request for one manual or passive breeding attempt. */
public record BreedingPopulationAdmissionRequest(
        @Nonnull String worldName,
        int destinationChunkX,
        int destinationChunkZ,
        @Nonnull List<PlannedChild> plannedChildren,
        int maximumAdmittedCount,
        boolean force,
        @Nonnull String idempotencyKey,
        @Nonnull BreedingBirthPlanSnapshot birthPlan,
        @Nonnull List<String> parentProfileIds
) {
    public BreedingPopulationAdmissionRequest {
        worldName = normalizeText(worldName, "worldName");
        idempotencyKey = normalizeText(idempotencyKey, "idempotencyKey");
        birthPlan = Objects.requireNonNull(birthPlan, "birthPlan");
        parentProfileIds = normalizeParentProfileIds(parentProfileIds);
        if (plannedChildren == null || plannedChildren.isEmpty()) {
            throw new IllegalArgumentException("At least one planned child is required.");
        }
        plannedChildren = List.copyOf(plannedChildren);
        Set<String> keys = new HashSet<>();
        for (PlannedChild child : plannedChildren) {
            Objects.requireNonNull(child, "plannedChildren cannot contain null");
            if (!keys.add(child.childKey())) {
                throw new IllegalArgumentException("Duplicate planned child key: " + child.childKey());
            }
        }
        Set<String> fullPlanKeys = new HashSet<>();
        for (BreedingBirthPlanSnapshot.PlannedChild child : birthPlan.children()) {
            fullPlanKeys.add(child.childKey());
        }
        if (!fullPlanKeys.containsAll(keys)) {
            throw new IllegalArgumentException("Every requested child must belong to the durable birth plan.");
        }
        if (maximumAdmittedCount < 0) {
            throw new IllegalArgumentException("maximumAdmittedCount cannot be negative.");
        }
    }

    /**
     * Compatibility constructor for callers that have not yet resolved canonical parent profiles.
     * Such requests retain exact-attempt replay but deliberately cannot participate in pair lookup.
     */
    public BreedingPopulationAdmissionRequest(
            @Nonnull String worldName,
            int destinationChunkX,
            int destinationChunkZ,
            @Nonnull List<PlannedChild> plannedChildren,
            int maximumAdmittedCount,
            boolean force,
            @Nonnull String idempotencyKey,
            @Nonnull BreedingBirthPlanSnapshot birthPlan
    ) {
        this(
                worldName,
                destinationChunkX,
                destinationChunkZ,
                plannedChildren,
                maximumAdmittedCount,
                force,
                idempotencyKey,
                birthPlan,
                List.of()
        );
    }

    public int boundedAdmittedCount() {
        return Math.min(plannedChildren.size(), maximumAdmittedCount);
    }

    public boolean hasCanonicalParentPair() {
        return parentProfileIds.size() == 2;
    }

    /** Exact owner already selected using the same rule that will initialize this child. */
    public record PlannedChild(
            @Nonnull String childKey,
            @Nullable UUID ownerId,
            @Nullable String ownerName
    ) {
        public PlannedChild {
            childKey = normalizeText(childKey, "childKey");
            ownerName = ownerName == null || ownerName.isBlank() ? null : ownerName.trim();
        }
    }

    @Nonnull
    private static String normalizeText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank.");
        }
        return normalized;
    }

    @Nonnull
    static List<String> normalizeParentProfileIds(@Nonnull List<String> profileIds) {
        Objects.requireNonNull(profileIds, "parentProfileIds");
        TreeSet<String> canonical = new TreeSet<>();
        for (String profileId : profileIds) {
            canonical.add(normalizeText(profileId, "parentProfileIds"));
        }
        if (canonical.isEmpty()) {
            return List.of();
        }
        if (canonical.size() != 2) {
            throw new IllegalArgumentException(
                    "A canonical breeding pair requires two distinct parent profile IDs."
            );
        }
        return List.copyOf(canonical);
    }
}

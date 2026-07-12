package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Creates and verifies the durable marker for one journal-reserved breeding child.
 * Generation stays at one because the attempt and child key already identify the occurrence;
 * replay filtering must not renumber a surviving marker.
 */
public final class BreedingChildProjectionMarker {
    private BreedingChildProjectionMarker() {
    }

    @Nonnull
    public static TameworkProjectionIdentityComponent create(
            @Nonnull String attemptKey,
            @Nonnull String childKey,
            @Nonnull String profileId,
            @Nonnull UUID plannedNpcUuid) {
        return new TameworkProjectionIdentityComponent(
                requireText(profileId, "profileId"),
                requireText(attemptKey, "attemptKey"),
                TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD,
                requireText(childKey, "childKey"),
                Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid"),
                1L
        );
    }

    public static boolean matches(
            @Nullable TameworkProjectionIdentityComponent marker,
            @Nonnull TameworkProjectionIdentityComponent expected) {
        return marker != null
                && TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD.equals(
                        marker.getProjectionKind()
                )
                && Objects.equals(expected.getProfileId(), marker.getProfileId())
                && Objects.equals(expected.getOperationId(), marker.getOperationId())
                && Objects.equals(expected.getSlotKey(), marker.getSlotKey())
                && Objects.equals(expected.getSourceNpcUuid(), marker.getSourceNpcUuid())
                && expected.getGeneration() == marker.getGeneration();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}

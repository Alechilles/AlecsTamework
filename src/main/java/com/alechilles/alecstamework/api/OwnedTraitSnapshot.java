package com.alechilles.alecstamework.api;

import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable trait evidence read from a stored companion snapshot.
 *
 * <p>This is deliberately detached from live ECS state. A false
 * {@link #traitDataAvailable()} means consumers must not score or render the
 * row's trait values as neutral values. {@link #snapshotCreatedAtMs()} is zero
 * when no usable saved trait snapshot exists.</p>
 */
public record OwnedTraitSnapshot(
        @Nonnull String profileId,
        @Nullable String roleId,
        @Nullable String displayName,
        @Nullable String traitConfigId,
        @Nonnull List<ProgressionView.TraitValueView> traits,
        boolean traitDataAvailable,
        long snapshotCreatedAtMs
) {
    public OwnedTraitSnapshot {
        if (profileId == null || profileId.isBlank() || traits == null
                || snapshotCreatedAtMs < 0L) {
            throw new IllegalArgumentException(
                    "Complete owned trait snapshot identity is required"
            );
        }
        profileId = profileId.trim();
        roleId = normalize(roleId);
        displayName = normalize(displayName);
        traitConfigId = normalize(traitConfigId);
        traits = List.copyOf(traits);
        if (!traitDataAvailable && !traits.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unavailable trait data cannot carry presentation values"
            );
        }
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

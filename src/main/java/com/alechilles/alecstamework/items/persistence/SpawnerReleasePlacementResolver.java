package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Freezes one safe spawn transform before a captured-artifact release is submitted. */
@FunctionalInterface
public interface SpawnerReleasePlacementResolver {
    @Nullable
    CompanionSpawnPlacement freeze(
            @Nonnull SpawnerCapturedArtifactReleaseIntent intent
    );
}

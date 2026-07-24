package com.alechilles.alecstamework.items.persistence;

import java.util.UUID;

/**
 * Immutable actor context that may safely cross the captured-artifact release async boundary.
 *
 * <p>Inventory values and live game objects are intentionally excluded.</p>
 */
record SpawnerCaptureReleaseContext(
        UUID actorUuid,
        String worldKey,
        int sourceSlot,
        SpawnerPublishedEffect publishedEffect
) {
}

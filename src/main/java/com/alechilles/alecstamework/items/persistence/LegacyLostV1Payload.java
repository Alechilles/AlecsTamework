package com.alechilles.alecstamework.items.persistence;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Immutable value for the lost snapshot payload written by the June persistence system.
 *
 * <p>The source NPC UUID was profile identity outside this payload. It is deliberately not part
 * of this versioned value.</p>
 */
public record LegacyLostV1Payload(
        @Nullable SnapshotVector3 lastKnownPosition,
        @Nullable SnapshotVector3 homePosition,
        long lastRelocationQueuedAtMs,
        long lostAtMs,
        int relocationRetryAttempts,
        @Nullable UUID replacementNpcUuid,
        long recoveredAtMs
) {
}

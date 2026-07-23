package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import javax.annotation.Nonnull;

/**
 * Crash-self-sufficient spawn state resolved from one exact dormant source snapshot.
 *
 * <p>The source alias remains readable before decoding full state so a live boundary can resolve
 * an existing exact receipt before depending on a historical payload codec.</p>
 */
public record RestorationProjection(
        @Nonnull NpcAlias sourceAlias,
        @Nonnull SnapshotCodecRegistry.EncodedSnapshot fullState
) {
    public RestorationProjection {
        if (sourceAlias == null || fullState == null) {
            throw new IllegalArgumentException("Complete restoration projection is required");
        }
    }
}

package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;

/** Immutable full-holder capture made on the entity-store thread. */
public record CompanionEntityCheckpointCapture(
        @Nonnull NpcAlias alias,
        @Nonnull OwnerId ownerId,
        @Nonnull String worldKey,
        double x,
        double y,
        double z,
        @Nonnull CompanionEntityCheckpoint.CaptureBoundary boundary,
        long capturedAtMs,
        @Nonnull BsonDocument holder
) {
    public CompanionEntityCheckpointCapture {
        if (alias == null || ownerId == null || boundary == null
                || holder == null || worldKey == null
                || worldKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Complete companion entity capture is required"
            );
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            throw new IllegalArgumentException(
                    "Finite companion capture position is required"
            );
        }
        worldKey = worldKey.trim();
        holder = holder.clone();
    }

    @Override
    @Nonnull
    public BsonDocument holder() {
        return holder.clone();
    }
}

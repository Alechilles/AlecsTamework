package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;

/**
 * Immutable identity-fenced snapshot of every serializable NPC component.
 *
 * <p>The BSON holder is the authoritative state. Named fields are only the
 * fences needed to decide whether a later restore is safe.</p>
 */
public record CompanionEntityCheckpoint(
        int version,
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias alias,
        long aliasGeneration,
        @Nonnull OwnerId ownerId,
        @Nonnull LifecycleRevision lifecycleRevision,
        @Nonnull ReconciliationGeneration reconciliationGeneration,
        @Nonnull String worldKey,
        double x,
        double y,
        double z,
        @Nonnull CaptureBoundary boundary,
        long capturedAtMs,
        @Nonnull BsonDocument holder,
        @Nonnull Sha256Hash payloadHash
) {
    public static final int VERSION = 1;

    public CompanionEntityCheckpoint {
        if (version != VERSION || profileId == null || alias == null
                || ownerId == null || lifecycleRevision == null
                || reconciliationGeneration == null || boundary == null
                || holder == null || payloadHash == null
                || aliasGeneration < 0) {
            throw new IllegalArgumentException(
                    "Complete companion checkpoint identity is required"
            );
        }
        worldKey = requireText(worldKey);
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            throw new IllegalArgumentException(
                    "Finite checkpoint position is required"
            );
        }
        holder = holder.clone();
    }

    /** Creates a checkpoint with an integrity hash over its canonical body. */
    @Nonnull
    public static CompanionEntityCheckpoint create(
            @Nonnull ProfileId profileId,
            @Nonnull NpcAlias alias,
            long aliasGeneration,
            @Nonnull OwnerId ownerId,
            @Nonnull LifecycleRevision lifecycleRevision,
            @Nonnull ReconciliationGeneration reconciliationGeneration,
            @Nonnull String worldKey,
            double x,
            double y,
            double z,
            @Nonnull CaptureBoundary boundary,
            long capturedAtMs,
            @Nonnull BsonDocument holder,
            @Nonnull CompanionEntityCheckpointCodec codec
    ) {
        Objects.requireNonNull(codec, "codec");
        CompanionEntityCheckpoint unsigned = new CompanionEntityCheckpoint(
                VERSION, profileId, alias, aliasGeneration, ownerId,
                lifecycleRevision, reconciliationGeneration, worldKey,
                x, y, z, boundary, capturedAtMs, holder,
                Sha256Hash.ofUtf8("unsigned")
        );
        return new CompanionEntityCheckpoint(
                VERSION, profileId, alias, aliasGeneration, ownerId,
                lifecycleRevision, reconciliationGeneration, worldKey,
                x, y, z, boundary, capturedAtMs, holder,
                Sha256Hash.ofUtf8(codec.integrityMaterial(unsigned))
        );
    }

    @Override
    @Nonnull
    public BsonDocument holder() {
        return holder.clone();
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Checkpoint world key is required"
            );
        }
        return value.trim();
    }

    /** World-thread boundary where the serialized holder was observed. */
    public enum CaptureBoundary {
        LOADED,
        UNLOAD,
        DESTRUCTIVE_REMOVE,
        RETURNED_RETIRED_ORIGINAL
    }
}

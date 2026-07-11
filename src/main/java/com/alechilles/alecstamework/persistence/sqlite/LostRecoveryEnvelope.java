package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Complete persistence view used to decide whether a lost profile can be recovered safely. */
public record LostRecoveryEnvelope(int formatVersion,
                                   @Nonnull String profileId,
                                   @Nonnull UUID currentNpcUuid,
                                   @Nullable UUID sourceNpcUuid,
                                   @Nonnull LostMetadata metadata,
                                   @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot,
                                   @Nullable String fullSnapshotSha256) {
    public LostRecoveryEnvelope {
        if (formatVersion < 0) {
            throw new IllegalArgumentException("formatVersion must be non-negative");
        }
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId is required");
        }
        profileId = profileId.trim();
        Objects.requireNonNull(currentNpcUuid, "currentNpcUuid");
        Objects.requireNonNull(metadata, "metadata");
        if (fullSnapshotSha256 != null) {
            fullSnapshotSha256 = fullSnapshotSha256.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public boolean isAwaitingRecovery() {
        return metadata.replacementNpcUuid() == null;
    }

    public boolean hasVerifiedFullState() {
        return fullSnapshot != null && fullSnapshotSha256 != null && !fullSnapshotSha256.isBlank();
    }

    /** Legacy-compatible lost-location and retry metadata retained inside every envelope. */
    public record LostMetadata(@Nullable Vector3d lastKnownPosition,
                               @Nullable Vector3d homePosition,
                               long lastRelocationQueuedAtMs,
                               long lostAtMs,
                               int relocationRetryAttempts,
                               @Nullable UUID replacementNpcUuid,
                               long recoveredAtMs) {
        public LostMetadata {
            lastKnownPosition = copy(lastKnownPosition);
            homePosition = copy(homePosition);
        }

        @Override
        @Nullable
        public Vector3d lastKnownPosition() {
            return copy(lastKnownPosition);
        }

        @Override
        @Nullable
        public Vector3d homePosition() {
            return copy(homePosition);
        }

        @Nullable
        private static Vector3d copy(@Nullable Vector3d value) {
            return value == null ? null : new Vector3d(value);
        }
    }
}

package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed result for reusing a finalized recovery envelope after the live snapshot cache is lost. */
public record RecoveredProjectionSnapshotLoadResult(
        @Nonnull Status status,
        @Nullable String profileId,
        @Nullable UUID sourceNpcUuid,
        @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
        @Nonnull String reason,
        @Nullable Throwable cause) {

    public enum Status {
        FOUND,
        NOT_FOUND,
        CONFLICT,
        FAILED
    }

    @Nonnull
    public static RecoveredProjectionSnapshotLoadResult found(
            @Nonnull String profileId,
            @Nonnull UUID sourceNpcUuid,
            @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot) {
        return new RecoveredProjectionSnapshotLoadResult(
                Status.FOUND, profileId, sourceNpcUuid, snapshot, "verified_recovered_projection", null);
    }

    @Nonnull
    public static RecoveredProjectionSnapshotLoadResult notFound(@Nonnull String reason) {
        return new RecoveredProjectionSnapshotLoadResult(
                Status.NOT_FOUND, null, null, null, normalize(reason), null);
    }

    @Nonnull
    public static RecoveredProjectionSnapshotLoadResult conflict(
            @Nullable String profileId,
            @Nullable UUID sourceNpcUuid,
            @Nonnull String reason) {
        return new RecoveredProjectionSnapshotLoadResult(
                Status.CONFLICT, profileId, sourceNpcUuid, null, normalize(reason), null);
    }

    @Nonnull
    public static RecoveredProjectionSnapshotLoadResult failed(
            @Nullable String profileId,
            @Nullable UUID sourceNpcUuid,
            @Nonnull String reason,
            @Nullable Throwable cause) {
        return new RecoveredProjectionSnapshotLoadResult(
                Status.FAILED, profileId, sourceNpcUuid, null, normalize(reason), cause);
    }

    public boolean isFound() {
        return status == Status.FOUND && snapshot != null;
    }

    @Nonnull
    private static String normalize(@Nullable String reason) {
        return reason == null || reason.isBlank() ? "unknown" : reason.trim();
    }
}

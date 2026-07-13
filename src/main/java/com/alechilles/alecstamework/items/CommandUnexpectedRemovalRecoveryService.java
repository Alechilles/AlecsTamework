package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.RemoveReason;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Converts a destructive disappearance of a linked companion into the existing strict Lost recovery flow.
 *
 * <p>Normal chunk unloads retain their physical projection and must continue through relocation. A raw
 * {@link RemoveReason#REMOVE} has no projection left to relocate, so recovery is safe only when the last
 * live ECS boundary supplied a complete restorable snapshot.</p>
 */
public final class CommandUnexpectedRemovalRecoveryService {
    private final LostRecorder lostRecorder;

    public CommandUnexpectedRemovalRecoveryService(@Nonnull CommandLinkedNpcLostService lostService) {
        Objects.requireNonNull(lostService, "lostService");
        this.lostRecorder = request -> lostService.recordLostFromRelocationDrop(
                request.npcUuid(),
                request.ownerUuid(),
                request.lastKnownPosition(),
                request.homePosition(),
                null,
                request.removedAtMs(),
                request.removedAtMs(),
                0
        );
    }

    CommandUnexpectedRemovalRecoveryService(@Nonnull LostRecorder lostRecorder) {
        this.lostRecorder = Objects.requireNonNull(lostRecorder, "lostRecorder");
    }

    /** Submits a Lost transition only for a restorable linked companion destroyed outside a death flow. */
    @Nonnull
    public Result recordIfRecoverable(@Nonnull RemovalEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.reason() != RemoveReason.REMOVE) {
            return Result.SKIPPED_NOT_DESTRUCTIVE;
        }
        if (evidence.intentionalHandoff()) {
            return Result.SKIPPED_INTENTIONAL_HANDOFF;
        }
        if (evidence.npcUuid() == null || !evidence.restorableSnapshotAvailable()) {
            return Result.SKIPPED_NO_RECOVERY_SNAPSHOT;
        }
        if (evidence.deathTracked() || evidence.permanentDeathReleased()) {
            return Result.SKIPPED_DEATH;
        }
        lostRecorder.record(new LostRequest(
                evidence.npcUuid(),
                evidence.ownerUuid(),
                copy(evidence.lastKnownPosition()),
                copy(evidence.homePosition()),
                evidence.removedAtMs()
        ));
        return Result.SUBMITTED;
    }

    @Nullable
    private static Vector3d copy(@Nullable Vector3d value) {
        return value != null ? new Vector3d(value) : null;
    }

    public enum Result {
        SUBMITTED,
        SKIPPED_NOT_DESTRUCTIVE,
        SKIPPED_INTENTIONAL_HANDOFF,
        SKIPPED_NO_RECOVERY_SNAPSHOT,
        SKIPPED_DEATH
    }

    public record RemovalEvidence(@Nullable UUID npcUuid,
                                  @Nonnull RemoveReason reason,
                                  @Nullable UUID ownerUuid,
                                  @Nullable Vector3d lastKnownPosition,
                                  @Nullable Vector3d homePosition,
                                  boolean restorableSnapshotAvailable,
                                  boolean deathTracked,
                                  boolean permanentDeathReleased,
                                  boolean intentionalHandoff,
                                  long removedAtMs) {
        public RemovalEvidence {
            Objects.requireNonNull(reason, "reason");
            lastKnownPosition = copy(lastKnownPosition);
            homePosition = copy(homePosition);
        }
    }

    record LostRequest(@Nonnull UUID npcUuid,
                       @Nullable UUID ownerUuid,
                       @Nullable Vector3d lastKnownPosition,
                       @Nullable Vector3d homePosition,
                       long removedAtMs) {
    }

    @FunctionalInterface
    interface LostRecorder {
        void record(@Nonnull LostRequest request);
    }
}

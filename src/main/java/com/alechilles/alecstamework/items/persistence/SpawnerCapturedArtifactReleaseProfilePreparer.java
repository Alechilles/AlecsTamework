package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureReleaseEvidenceFreezer.FrozenRelease;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Validates one captured profile and prepares its source-neutral release projection.
 *
 * <p>This collaborator owns snapshot selection, captured-lifecycle matching, and ownership
 * normalization so the release author can remain a workflow orchestrator.</p>
 */
final class SpawnerCapturedArtifactReleaseProfilePreparer {
    private final SpawnerCaptureSnapshotMapper snapshots;
    private final SpawnerFullStateOwnershipNormalizer ownership;

    SpawnerCapturedArtifactReleaseProfilePreparer(
            SpawnerCaptureSnapshotMapper snapshots
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.ownership = new SpawnerFullStateOwnershipNormalizer();
    }

    Result prepare(
            CompanionProfileReadModel profile,
            FrozenRelease frozen
    ) {
        CompanionSnapshot sourceSnapshot = exactCaptureSnapshot(
                profile, frozen
        );
        if (sourceSnapshot == null || !exactCapturedProfile(
                profile, frozen, sourceSnapshot
        )) {
            return Rejected.profileConflict();
        }
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                snapshots.decodeCapture(sourceSnapshot);
        if (!(decoded instanceof SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshot> found)
                || !frozen.sourceAlias().value().equals(
                found.value().npcUuid()
        )) {
            return Rejected.decodeFailed(decodeDetail(decoded), null);
        }
        return prepareProjection(
                profile, frozen, sourceSnapshot, found.value()
        );
    }

    private Result prepareProjection(
            CompanionProfileReadModel profile,
            FrozenRelease frozen,
            CompanionSnapshot sourceSnapshot,
            CoopResidentStateSnapshot decoded
    ) {
        OwnerId ownerAssignment = frozen.ownerAssignment();
        OwnerId canonicalOwner = profile.lifecycle().ownerId();
        if (ownerAssignment != null && canonicalOwner != null) {
            return Rejected.profileConflict();
        }
        OwnerId effectiveOwner = ownerAssignment == null
                ? canonicalOwner
                : ownerAssignment;
        String effectiveOwnerName = ownerAssignment == null
                ? projectedOwnerName(profile)
                : frozen.ownerAssignmentName();
        try {
            return new Prepared(
                    sourceSnapshot,
                    snapshots.encodeProjection(ownership.normalize(
                            decoded,
                            effectiveOwner,
                            effectiveOwnerName
                    ))
            );
        } catch (RuntimeException failure) {
            return Rejected.decodeFailed(
                    "capture_projection_encode_failed", failure
            );
        }
    }

    @Nullable
    private CompanionSnapshot exactCaptureSnapshot(
            CompanionProfileReadModel profile,
            FrozenRelease frozen
    ) {
        CompanionSnapshot exact = null;
        for (CompanionSnapshot snapshot : profile.currentSnapshots()) {
            if (!snapshot.kind().equals(
                    CompanionCaptureRequest.SNAPSHOT_KIND
            )) {
                continue;
            }
            if (!snapshot.snapshotId().equals(frozen.snapshotId())
                    || exact != null) {
                return null;
            }
            exact = snapshot;
        }
        return exact;
    }

    private boolean exactCapturedProfile(
            CompanionProfileReadModel profile,
            FrozenRelease frozen,
            CompanionSnapshot sourceSnapshot
    ) {
        CompanionAlias alias = profile.currentAlias();
        CompanionLifecycle lifecycle = profile.lifecycle();
        return profile.identity().profileId().equals(frozen.profileId())
                && alias != null
                && alias.alias().equals(frozen.sourceAlias())
                && alias.state() == CompanionAlias.State.CURRENT
                && lifecycle.state() == LifecycleState.CAPTURED
                && lifecycle.location().equals(LifecycleLocation.keyed(
                LifecycleLocationKind.CAPTURE_ITEM,
                sourceSnapshot.snapshotId().toString()
        ))
                && lifecycle.activeOperationId() == null
                && !lifecycle.quarantined();
    }

    @Nullable
    private String projectedOwnerName(
            CompanionProfileReadModel profile
    ) {
        try {
            return CompanionProfileProjectionState.compose(
                    profile.identity(),
                    profile.currentAlias(),
                    profile.lifecycle(),
                    profile.toolLinks(),
                    profile.currentSnapshots(),
                    profile.currentCoopSlot()
            ).ownerName();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private String decodeDetail(
            SnapshotDecodeResult<CoopResidentStateSnapshot> decoded
    ) {
        return decoded instanceof SnapshotDecodeResult.Failed<?> failed
                ? failed.code()
                : "capture_snapshot_alias_mismatch";
    }

    sealed interface Result permits Prepared, Rejected {
    }

    record Prepared(
            CompanionSnapshot sourceSnapshot,
            SnapshotCodecRegistry.EncodedSnapshot projection
    ) implements Result {
    }

    record Rejected(
            SpawnerPersistenceAuthorResult.Status status,
            String detail,
            Throwable failure
    ) implements Result {
        private static Rejected profileConflict() {
            return new Rejected(
                    SpawnerPersistenceAuthorResult.Status.PROFILE_CONFLICT,
                    "capture_release_profile_not_exact_captured",
                    null
            );
        }

        private static Rejected decodeFailed(
                String detail,
                Throwable failure
        ) {
            return new Rejected(
                    SpawnerPersistenceAuthorResult.Status
                            .SNAPSHOT_DECODE_FAILED,
                    detail,
                    failure
            );
        }
    }
}

package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
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
import com.alechilles.alecstamework.items.CompanionReturnStateNormalizer;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureReleaseEvidenceFreezer.ResolvedIdentity;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactIdentity.Claim;
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
    private final LegacyCapturedArtifactFullStateMapper legacy;

    SpawnerCapturedArtifactReleaseProfilePreparer(
            SpawnerCaptureSnapshotMapper snapshots
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.ownership = new SpawnerFullStateOwnershipNormalizer();
        this.legacy = new LegacyCapturedArtifactFullStateMapper();
    }

    Result prepare(
            CompanionProfileReadModel profile,
            Claim claim,
            CapturedArtifact sourceArtifact,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName
    ) {
        CompanionSnapshot sourceSnapshot = exactCaptureSnapshot(
                profile, claim
        );
        if (sourceSnapshot == null
                || !exactCapturedProfile(profile, claim, sourceSnapshot)) {
            return Rejected.profileConflict();
        }
        final CoopResidentStateSnapshot decoded;
        try {
            decoded = decode(
                    profile, claim, sourceSnapshot, sourceArtifact
            );
        } catch (RuntimeException failure) {
            return Rejected.decodeFailed(
                    "capture_snapshot_decode_failed", failure
            );
        }
        if (!claim.sourceAlias().value().equals(decoded.npcUuid())) {
            return Rejected.decodeFailed(
                    "capture_snapshot_alias_mismatch", null
            );
        }
        return prepareProjection(
                profile,
                sourceSnapshot,
                decoded,
                ownerAssignment,
                ownerAssignmentName
        );
    }

    Result prepareLegacyRecovery(
            CompanionProfileReadModel profile,
            CompanionSnapshot historicalSnapshot,
            CapturedArtifact sourceArtifact,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName
    ) {
        if (historicalSnapshot == null || historicalSnapshot.current()
                || historicalSnapshot.payloadVersion()
                != LegacyCaptureV1Payload.VERSION) {
            return Rejected.profileConflict();
        }
        final CoopResidentStateSnapshot decoded;
        try {
            decoded = legacy.map(
                    profile,
                    LegacyCaptureV1Payload.decode(historicalSnapshot),
                    sourceArtifact
            );
        } catch (RuntimeException failure) {
            return Rejected.decodeFailed(
                    "capture_snapshot_decode_failed",
                    failure
            );
        }
        CompanionSnapshot liveSource = new CompanionSnapshot(
                historicalSnapshot.snapshotId(),
                historicalSnapshot.profileId(),
                historicalSnapshot.kind(),
                historicalSnapshot.payloadVersion(),
                historicalSnapshot.payloadJson(),
                historicalSnapshot.payloadHash(),
                historicalSnapshot.sourceLifecycleRevision(),
                true,
                historicalSnapshot.createdAtMs()
        );
        return prepareProjection(
                profile,
                liveSource,
                decoded,
                ownerAssignment,
                ownerAssignmentName
        );
    }

    Result prepareModernRecovery(
            CompanionProfileReadModel profile,
            Claim claim,
            CompanionSnapshot canonicalSnapshot,
            CapturedArtifact sourceArtifact,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName
    ) {
        if (claim == null || claim.releasedPublic()
                || claim.snapshotId() == null
                || canonicalSnapshot == null
                || (canonicalSnapshot.payloadVersion()
                != LegacyCaptureV1Payload.VERSION
                && canonicalSnapshot.payloadVersion()
                != CompanionCaptureRequest.SNAPSHOT_VERSION)) {
            return Rejected.profileConflict();
        }
        final CoopResidentStateSnapshot reconstructed;
        try {
            LegacyCaptureV1Payload seed = canonicalSnapshot.payloadVersion()
                    == LegacyCaptureV1Payload.VERSION
                    ? LegacyCaptureV1Payload.decode(canonicalSnapshot)
                    : legacySeed(profile, canonicalSnapshot);
            reconstructed = legacy.map(profile, seed, sourceArtifact);
        } catch (RuntimeException failure) {
            return Rejected.decodeFailed(
                    "capture_snapshot_decode_failed",
                    failure
            );
        }
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                snapshots.encodeCapture(reconstructed);
        CompanionSnapshot liveSource = new CompanionSnapshot(
                claim.snapshotId(),
                profile.identity().profileId(),
                CompanionCaptureRequest.SNAPSHOT_KIND,
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                profile.lifecycle().revision(),
                true,
                canonicalSnapshot.createdAtMs()
        );
        Result result = prepareProjection(
                profile,
                liveSource,
                reconstructed,
                ownerAssignment,
                ownerAssignmentName
        );
        if (!(result instanceof Prepared ready)) {
            return result;
        }
        return new Prepared(
                ready.sourceSnapshot(),
                ready.projection(),
                new ResolvedIdentity(
                        profile.identity().profileId(),
                        claim.sourceAlias(),
                        claim.snapshotId()
                ),
                ready.ownerAssignment(),
                ready.ownerAssignmentName()
        );
    }

    private LegacyCaptureV1Payload legacySeed(
            CompanionProfileReadModel profile,
            CompanionSnapshot canonicalSnapshot
    ) {
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                snapshots.decodeCapture(canonicalSnapshot);
        if (!(decoded instanceof SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshot> found)) {
            throw new IllegalArgumentException(decodeDetail(decoded));
        }
        CoopResidentStateSnapshot state = found.value();
        org.joml.Vector3d home = state.commandLinks() == null
                ? null
                : state.commandLinks().getHomePosition();
        return new LegacyCaptureV1Payload(
                null,
                home == null ? null : new SnapshotVector3(
                        home.x,
                        home.y,
                        home.z
                ),
                state.capturedAtMs(),
                state.roleId(),
                state.npcName() == null
                        ? profile.identity().displayName()
                        : state.npcName().getName()
        );
    }

    private CoopResidentStateSnapshot decode(
            CompanionProfileReadModel profile,
            Claim claim,
            CompanionSnapshot sourceSnapshot,
            CapturedArtifact sourceArtifact
    ) {
        if (claim.releasedPublic()) {
            return legacy.map(
                    profile,
                    LegacyCaptureV1Payload.decode(sourceSnapshot),
                    sourceArtifact
            );
        }
        if (sourceSnapshot.payloadVersion()
                != CompanionCaptureRequest.SNAPSHOT_VERSION) {
            throw new IllegalArgumentException(
                    "Current captured artifact requires capture-v2"
            );
        }
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                snapshots.decodeCapture(sourceSnapshot);
        if (decoded instanceof SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshot> found) {
            return found.value();
        }
        throw new IllegalArgumentException(decodeDetail(decoded));
    }

    Result prepareProjection(
            CompanionProfileReadModel profile,
            CompanionSnapshot sourceSnapshot,
            CoopResidentStateSnapshot decoded,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName
    ) {
        OwnerId canonicalOwner = profile.lifecycle().ownerId();
        if (ownerAssignment != null && canonicalOwner != null
                && !ownerAssignment.equals(canonicalOwner)) {
            return Rejected.profileConflict();
        }
        OwnerId effectiveOwner = canonicalOwner == null
                ? ownerAssignment
                : canonicalOwner;
        String effectiveOwnerName = canonicalOwner == null
                ? ownerAssignmentName
                : projectedOwnerName(profile);
        OwnerId durableAssignment = canonicalOwner == null
                ? ownerAssignment
                : null;
        String durableAssignmentName = durableAssignment == null
                ? null
                : ownerAssignmentName;
        try {
            return new Prepared(
                    sourceSnapshot,
                    snapshots.encodeProjection(ownership.normalize(
                            CompanionReturnStateNormalizer.forCaptureRelease(
                                    decoded
                            ),
                            effectiveOwner,
                            effectiveOwnerName
                    )),
                    new ResolvedIdentity(
                            profile.identity().profileId(),
                            profile.currentAlias().alias(),
                            sourceSnapshot.snapshotId()
                    ),
                    durableAssignment,
                    durableAssignmentName
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
            Claim claim
    ) {
        if (profile == null || claim == null
                || profile.lifecycle().location().kind()
                != LifecycleLocationKind.CAPTURE_ITEM) {
            return null;
        }
        final String locationKey = profile.lifecycle().location().key();
        CompanionSnapshot exact = null;
        for (CompanionSnapshot snapshot : profile.currentSnapshots()) {
            if (!snapshot.kind().equals(
                    CompanionCaptureRequest.SNAPSHOT_KIND
            )) {
                continue;
            }
            if (!snapshot.snapshotId().toString().equals(locationKey)
                    || exact != null) {
                return null;
            }
            exact = snapshot;
        }
        if (exact != null && claim.snapshotId() != null
                && !claim.snapshotId().equals(exact.snapshotId())) {
            return null;
        }
        return exact;
    }

    private boolean exactCapturedProfile(
            CompanionProfileReadModel profile,
            Claim claim,
            CompanionSnapshot sourceSnapshot
    ) {
        CompanionAlias alias = profile.currentAlias();
        CompanionLifecycle lifecycle = profile.lifecycle();
        return (claim.profileId() == null
                || profile.identity().profileId().equals(
                claim.profileId()
        ))
                && alias != null
                && alias.alias().equals(claim.sourceAlias())
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
            SnapshotCodecRegistry.EncodedSnapshot projection,
            ResolvedIdentity resolvedIdentity,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName
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

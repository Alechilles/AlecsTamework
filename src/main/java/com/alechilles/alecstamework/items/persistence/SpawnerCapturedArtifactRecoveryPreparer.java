package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureReleaseLegacyRecoveryEvidence;
import com.alechilles.alecstamework.companion.capture.CaptureReleaseModernRecoveryEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactIdentity.Claim;
import java.util.List;
import javax.annotation.Nullable;

/** Selects the one safe capture-v1 history row for an already-migrated legacy item. */
final class SpawnerCapturedArtifactRecoveryPreparer {
    private static final LifecycleRevision IMPORTED_UNLOADED_REVISION =
            new LifecycleRevision(1L);
    private static final ReconciliationGeneration IMPORTED_GENERATION =
            ReconciliationGeneration.INITIAL.next();

    private final SpawnerCapturedArtifactReleaseProfilePreparer projections;

    SpawnerCapturedArtifactRecoveryPreparer(
            SpawnerCapturedArtifactReleaseProfilePreparer projections
    ) {
        this.projections = projections;
    }

    Result prepare(
            CompanionProfileReadModel profile,
            Claim claim,
            CapturedArtifact artifact,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName,
            List<CompanionSnapshot> captureHistory,
            boolean sourceAliasAbsent
    ) {
        CompanionAlias alias = profile == null ? null : profile.currentAlias();
        if (claim == null || !claim.releasedPublic()
                || alias == null
                || alias.state() != CompanionAlias.State.CURRENT
                || !claim.sourceAlias().equals(alias.alias())
                || profile.lifecycle().state() != LifecycleState.UNLOADED
                || !profile.lifecycle().location().equals(
                LifecycleLocation.none()
        )
                || !profile.lifecycle().revision().equals(
                IMPORTED_UNLOADED_REVISION
        )
                || !profile.lifecycle().lastReconciledGeneration().equals(
                IMPORTED_GENERATION
        )
                || profile.lifecycle().activeOperationId() != null
                || profile.lifecycle().quarantined()
                || !profile.currentSnapshots().isEmpty()
                || !sourceAliasAbsent) {
            return Rejected.INSTANCE;
        }
        CompanionSnapshot historical = uniqueCandidate(
                profile,
                captureHistory
        );
        if (historical == null) {
            return Rejected.INSTANCE;
        }
        var projection = projections.prepareLegacyRecovery(
                profile,
                historical,
                artifact,
                ownerAssignment,
                ownerAssignmentName
        );
        if (!(projection instanceof
                SpawnerCapturedArtifactReleaseProfilePreparer.Prepared ready)) {
            return Rejected.INSTANCE;
        }
        return new Prepared(
                ready,
                new CaptureReleaseLegacyRecoveryEvidence(
                        historical,
                        profile.lifecycle().lastReconciledGeneration(),
                        alias.generation(),
                        alias.mappedAtMs()
                )
        );
    }

    Result prepareModern(
            CompanionProfileReadModel profile,
            Claim claim,
            CapturedArtifact artifact,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName,
            boolean canonicalAliasAbsent,
            boolean itemAliasAbsent
    ) {
        CompanionAlias alias = profile == null ? null : profile.currentAlias();
        CompanionSnapshot canonical = exactCurrentCapture(profile);
        if (claim == null || claim.releasedPublic()
                || claim.profileId() == null
                || !claim.profileId().equals(profile.identity().profileId())
                || alias == null
                || alias.state() != CompanionAlias.State.CURRENT
                || claim.sourceAlias().equals(alias.alias())
                || canonical == null
                || claim.snapshotId().equals(canonical.snapshotId())
                || profile.lifecycle().activeOperationId() != null
                || profile.lifecycle().quarantined()
                || !canonicalAliasAbsent
                || !itemAliasAbsent) {
            return Rejected.INSTANCE;
        }
        var projection = projections.prepareModernRecovery(
                profile,
                claim,
                canonical,
                artifact,
                ownerAssignment,
                ownerAssignmentName
        );
        if (!(projection instanceof
                SpawnerCapturedArtifactReleaseProfilePreparer.Prepared ready)) {
            return Rejected.INSTANCE;
        }
        return new ModernPrepared(
                ready,
                new CaptureReleaseModernRecoveryEvidence(
                        canonical,
                        alias.alias(),
                        profile.lifecycle().lastReconciledGeneration(),
                        alias.generation(),
                        alias.mappedAtMs()
                )
        );
    }

    @Nullable
    private CompanionSnapshot exactCurrentCapture(
            CompanionProfileReadModel profile
    ) {
        if (profile == null
                || profile.lifecycle().state() != LifecycleState.CAPTURED
                || profile.lifecycle().location().kind()
                != com.alechilles.alecstamework.companion.lifecycle
                .LifecycleLocationKind.CAPTURE_ITEM) {
            return null;
        }
        CompanionSnapshot result = null;
        for (CompanionSnapshot snapshot : profile.currentSnapshots()) {
            if (!CompanionCaptureRequest.SNAPSHOT_KIND.equals(snapshot.kind())) {
                continue;
            }
            if (result != null
                    || !snapshot.snapshotId().toString().equals(
                    profile.lifecycle().location().key()
            )) {
                return null;
            }
            result = snapshot;
        }
        return result;
    }

    @Nullable
    private CompanionSnapshot uniqueCandidate(
            CompanionProfileReadModel profile,
            List<CompanionSnapshot> history
    ) {
        CompanionSnapshot candidate = null;
        if (history == null) {
            return null;
        }
        for (CompanionSnapshot snapshot : history) {
            if (snapshot == null || snapshot.current()
                    || !profile.identity().profileId().equals(
                    snapshot.profileId()
            )
                    || !CompanionCaptureRequest.SNAPSHOT_KIND.equals(
                    snapshot.kind()
            )
                    || snapshot.payloadVersion()
                    != LegacyCaptureV1Payload.VERSION) {
                continue;
            }
            if (candidate != null) {
                return null;
            }
            candidate = snapshot;
        }
        return candidate;
    }

    sealed interface Result permits Prepared, ModernPrepared, Rejected {
    }

    record Prepared(
            SpawnerCapturedArtifactReleaseProfilePreparer.Prepared release,
            CaptureReleaseLegacyRecoveryEvidence evidence
    ) implements Result {
    }

    record ModernPrepared(
            SpawnerCapturedArtifactReleaseProfilePreparer.Prepared release,
            CaptureReleaseModernRecoveryEvidence evidence
    ) implements Result {
    }

    private enum Rejected implements Result {
        INSTANCE
    }
}

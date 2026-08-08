package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CaptureReleaseOrphanRecoveryEvidence;
import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.SpawnerCapturedArtifactIdentity.Claim;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import java.util.List;
import javax.annotation.Nullable;

/** Reconstructs the missing initial profile for one filled public item. */
final class SpawnerCapturedArtifactOrphanRecoveryPreparer {
    private final SpawnerCaptureSnapshotMapper snapshots;
    private final SpawnerCapturedArtifactReleaseProfilePreparer projections;
    private final LegacyCapturedArtifactFullStateMapper legacy =
            new LegacyCapturedArtifactFullStateMapper();

    SpawnerCapturedArtifactOrphanRecoveryPreparer(
            SpawnerCaptureSnapshotMapper snapshots,
            SpawnerCapturedArtifactReleaseProfilePreparer projections
    ) {
        this.snapshots = snapshots;
        this.projections = projections;
    }

    @Nullable
    Prepared prepare(
            Claim claim,
            CapturedArtifact artifact,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName,
            long requestedAtMs,
            boolean sourceAliasAbsent
    ) {
        if (claim == null || !claim.releasedPublic()
                || !sourceAliasAbsent) {
            return null;
        }
        LegacyCapturedArtifactMetadata item =
                LegacyCapturedArtifactMetadata.parse(artifact);
        String roleId = item.text(TameworkMetadataKeys.CAPTURE_ROLE_ID);
        if (roleId == null || !Boolean.TRUE.equals(
                item.bool(TameworkMetadataKeys.CAPTURED)
        )) {
            return null;
        }
        OwnerId initialOwner = owner(item);
        if (initialOwner != null && ownerAssignment != null) {
            return null;
        }
        ProfileId profileId = new ProfileId(claim.sourceAlias().value());
        SnapshotId snapshotId = SnapshotId.parse(
                claim.sourceAlias().toString()
        );
        String displayName = first(
                item.text(TameworkMetadataKeys.NPC_NAME),
                item.text(TameworkMetadataKeys.CAPTURE_TOOLTIP_DISPLAY_NAME)
        );
        String metadataJson = metadata(
                item,
                displayName,
                ownerAssignmentName
        );
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                displayName,
                roleId,
                metadataJson,
                Sha256Hash.ofUtf8(metadataJson),
                null,
                requestedAtMs,
                requestedAtMs,
                requestedAtMs,
                0L
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId,
                initialOwner,
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        snapshotId.toString()
                ),
                LifecycleRevision.INITIAL,
                null,
                requestedAtMs,
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
        CompanionProfileReadModel profile = new CompanionProfileReadModel(
                identity,
                new CompanionAlias(
                        claim.sourceAlias(),
                        profileId,
                        0L,
                        CompanionAlias.State.CURRENT,
                        null,
                        requestedAtMs,
                        null
                ),
                lifecycle,
                List.of(),
                List.of(),
                null
        );
        CoopResidentStateSnapshot state = legacy.map(
                profile,
                new LegacyCaptureV1Payload(
                        null,
                        null,
                        requestedAtMs,
                        roleId,
                        displayName
                ),
                artifact
        );
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                snapshots.encodeCapture(state);
        CompanionSnapshot source = new CompanionSnapshot(
                snapshotId,
                profileId,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                LifecycleRevision.INITIAL,
                true,
                requestedAtMs
        );
        var projected = projections.prepareProjection(
                profile,
                source,
                state,
                ownerAssignment,
                ownerAssignmentName
        );
        if (!(projected instanceof
                SpawnerCapturedArtifactReleaseProfilePreparer.Prepared ready)) {
            return null;
        }
        return new Prepared(
                profile,
                ready,
                new CaptureReleaseOrphanRecoveryEvidence(
                        identity,
                        initialOwner
                )
        );
    }

    @Nullable
    private OwnerId owner(LegacyCapturedArtifactMetadata item) {
        var owner = item.uuid(TameworkMetadataKeys.OWNER_UUID);
        return owner == null ? null : new OwnerId(owner);
    }

    private String metadata(
            LegacyCapturedArtifactMetadata item,
            @Nullable String displayName,
            @Nullable String ownerName
    ) {
        JsonObject json = new JsonObject();
        if (ownerName != null) {
            json.addProperty("owner_name", ownerName);
        }
        if (displayName != null) {
            json.addProperty("custom_name", displayName);
        }
        json.addProperty(
                "tamed",
                Boolean.TRUE.equals(item.bool(TameworkMetadataKeys.TAMED))
        );
        return json.toString();
    }

    @Nullable
    private String first(@Nullable String first, @Nullable String second) {
        return first == null ? second : first;
    }

    record Prepared(
            CompanionProfileReadModel profile,
            SpawnerCapturedArtifactReleaseProfilePreparer.Prepared release,
            CaptureReleaseOrphanRecoveryEvidence evidence
    ) {
    }
}

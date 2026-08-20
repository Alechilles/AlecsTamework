package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Immutable command to consume one exact captured artifact and restore its live companion. */
public record CompanionCaptureReleaseRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull CompanionSnapshot sourceSnapshot,
        @Nonnull NpcAlias sourceAlias,
        @Nonnull SnapshotCodecRegistry.EncodedSnapshot projection,
        @Nonnull CaptureReleaseSourceEvidence source,
        @Nonnull NpcAlias targetAlias,
        @Nullable OwnerId ownerAssignment,
        @Nonnull CompanionSpawnPlacement placement,
        @Nonnull String inventoryReceiptKey,
        @Nonnull String spawnReceiptKey,
        long requestedAtMs,
        @Nullable CaptureReleaseLegacyRecoveryEvidence legacyRecovery,
        @Nullable CaptureReleaseModernRecoveryEvidence modernRecovery,
        @Nullable CaptureReleaseOrphanRecoveryEvidence orphanRecovery,
        @Nullable LifecycleAdmissionEvidence admissionEvidence
) {
    /** Creates an ordinary captured-artifact release without recovery evidence. */
    public CompanionCaptureReleaseRequest(
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            CompanionSnapshot sourceSnapshot,
            NpcAlias sourceAlias,
            SnapshotCodecRegistry.EncodedSnapshot projection,
            CaptureReleaseSourceEvidence source,
            NpcAlias targetAlias,
            OwnerId ownerAssignment,
            CompanionSpawnPlacement placement,
            String inventoryReceiptKey,
            String spawnReceiptKey,
            long requestedAtMs
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                sourceSnapshot,
                sourceAlias,
                projection,
                source,
                targetAlias,
                ownerAssignment,
                placement,
                inventoryReceiptKey,
                spawnReceiptKey,
                requestedAtMs,
                null,
                null,
                null,
                null
        );
    }

    /** Creates a release carrying exact legacy-import recovery evidence. */
    public CompanionCaptureReleaseRequest(
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            CompanionSnapshot sourceSnapshot,
            NpcAlias sourceAlias,
            SnapshotCodecRegistry.EncodedSnapshot projection,
            CaptureReleaseSourceEvidence source,
            NpcAlias targetAlias,
            OwnerId ownerAssignment,
            CompanionSpawnPlacement placement,
            String inventoryReceiptKey,
            String spawnReceiptKey,
            long requestedAtMs,
            CaptureReleaseLegacyRecoveryEvidence legacyRecovery
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                sourceSnapshot,
                sourceAlias,
                projection,
                source,
                targetAlias,
                ownerAssignment,
                placement,
                inventoryReceiptKey,
                spawnReceiptKey,
                requestedAtMs,
                legacyRecovery,
                null,
                null,
                null
        );
    }

    /** Compatibility constructor for releases without item-only recovery. */
    public CompanionCaptureReleaseRequest(
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            CompanionSnapshot sourceSnapshot,
            NpcAlias sourceAlias,
            SnapshotCodecRegistry.EncodedSnapshot projection,
            CaptureReleaseSourceEvidence source,
            NpcAlias targetAlias,
            OwnerId ownerAssignment,
            CompanionSpawnPlacement placement,
            String inventoryReceiptKey,
            String spawnReceiptKey,
            long requestedAtMs,
            CaptureReleaseLegacyRecoveryEvidence legacyRecovery,
            CaptureReleaseModernRecoveryEvidence modernRecovery
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                sourceSnapshot,
                sourceAlias,
                projection,
                source,
                targetAlias,
                ownerAssignment,
                placement,
                inventoryReceiptKey,
                spawnReceiptKey,
                requestedAtMs,
                legacyRecovery,
                modernRecovery,
                null,
                null
        );
    }

    /** Source-compatible constructor with explicit recovery modes and no admission evidence. */
    public CompanionCaptureReleaseRequest(
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            CompanionSnapshot sourceSnapshot,
            NpcAlias sourceAlias,
            SnapshotCodecRegistry.EncodedSnapshot projection,
            CaptureReleaseSourceEvidence source,
            NpcAlias targetAlias,
            OwnerId ownerAssignment,
            CompanionSpawnPlacement placement,
            String inventoryReceiptKey,
            String spawnReceiptKey,
            long requestedAtMs,
            CaptureReleaseLegacyRecoveryEvidence legacyRecovery,
            CaptureReleaseModernRecoveryEvidence modernRecovery,
            CaptureReleaseOrphanRecoveryEvidence orphanRecovery
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                sourceSnapshot,
                sourceAlias,
                projection,
                source,
                targetAlias,
                ownerAssignment,
                placement,
                inventoryReceiptKey,
                spawnReceiptKey,
                requestedAtMs,
                legacyRecovery,
                modernRecovery,
                orphanRecovery,
                null
        );
    }

    public CompanionCaptureReleaseRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || sourceSnapshot == null || sourceAlias == null
                || projection == null || source == null || targetAlias == null
                || placement == null) {
            throw new IllegalArgumentException(
                    "Complete captured-artifact release request is required"
            );
        }
        inventoryReceiptKey = requireText(
                inventoryReceiptKey,
                "Captured-artifact release inventory receipt"
        );
        spawnReceiptKey = requireText(
                spawnReceiptKey,
                "Captured-artifact release spawn receipt"
        );
        if (!profileId.equals(sourceSnapshot.profileId())
                || !CompanionCaptureRequest.SNAPSHOT_KIND.equals(
                sourceSnapshot.kind()
        )
                || !sourceSnapshot.current()
                || !compatibleSourceRevision(
                sourceSnapshot.sourceLifecycleRevision(),
                expectedLifecycleRevision
        )) {
            throw new IllegalArgumentException(
                    "Captured-artifact release must reference the exact current capture snapshot"
            );
        }
        if (legacyRecovery != null
                && (!profileId.equals(
                legacyRecovery.historicalSnapshot().profileId()
        )
                || !sourceSnapshot.snapshotId().equals(
                legacyRecovery.historicalSnapshot().snapshotId()
        ))) {
            throw new IllegalArgumentException(
                    "Legacy recovery must reference the release source snapshot"
            );
        }
        int recoveryModes = (legacyRecovery == null ? 0 : 1)
                + (modernRecovery == null ? 0 : 1)
                + (orphanRecovery == null ? 0 : 1);
        if (recoveryModes > 1) {
            throw new IllegalArgumentException(
                    "Captured-artifact release has multiple recovery modes"
            );
        }
        if (modernRecovery != null
                && (!profileId.equals(
                modernRecovery.supersededSnapshot().profileId()
        )
                || sourceSnapshot.snapshotId().equals(
                modernRecovery.supersededSnapshot().snapshotId()
        ))) {
            throw new IllegalArgumentException(
                    "Modern recovery must supersede an older same-profile snapshot"
            );
        }
        if (orphanRecovery != null
                && (!profileId.equals(
                orphanRecovery.initialIdentity().profileId()
        )
                || !profileId.equals(new ProfileId(sourceAlias.value()))
                || !sourceSnapshot.snapshotId().toString().equals(
                sourceAlias.toString()
        )
                || !expectedLifecycleRevision.equals(
                LifecycleRevision.INITIAL
        )
                || orphanRecovery.initialOwner() != null
                && ownerAssignment != null)) {
            throw new IllegalArgumentException(
                    "Item-only recovery must create one exact initial captured profile"
            );
        }
        if (!CompanionFullStateProjection.KIND.equals(projection.kind())
                || projection.payloadVersion()
                != CompanionFullStateProjection.VERSION) {
            throw new IllegalArgumentException(
                    "Captured-artifact release requires full-state projection version one"
            );
        }
        if (!source.worldKey().equals(placement.worldKey())) {
            throw new IllegalArgumentException(
                    "Captured-artifact source and spawn must share one world boundary"
            );
        }
        if (sourceAlias.equals(targetAlias)) {
            throw new IllegalArgumentException(
                    "Captured-artifact release target alias must be distinct"
            );
        }
        requireSourceReceipt(
                source.sourceArtifact(),
                profileId,
                sourceAlias,
                sourceSnapshot.snapshotId().toString()
        );
        if (orphanRecovery != null
                && !isReleasedPublicCapture(
                source.sourceArtifact(), orphanRecovery
        )) {
            throw new IllegalArgumentException(
                    "Item-only recovery requires an exact released-public capture"
            );
        }
        requireReleaseReceipt(
                source.receiptArtifact(),
                inventoryReceiptKey
        );
        if (inventoryReceiptKey.equals(spawnReceiptKey)) {
            throw new IllegalArgumentException(
                "Captured-artifact inventory and spawn receipts must be distinct"
            );
        }
        requireAdmissionEvidence(
                admissionEvidence,
                profileId,
                expectedLifecycleRevision,
                source,
                ownerAssignment,
                placement,
                legacyRecovery,
                orphanRecovery
        );
    }

    private static boolean isReleasedPublicCapture(
            CapturedArtifact artifact,
            CaptureReleaseOrphanRecoveryEvidence recovery
    ) {
        BsonDocument metadata = BsonDocument.parse(
                artifact.metadataExtendedJson()
        );
        BsonValue captured = metadata.get(TameworkMetadataKeys.CAPTURED);
        BsonValue role = metadata.get(TameworkMetadataKeys.CAPTURE_ROLE_ID);
        BsonValue owner = metadata.get(TameworkMetadataKeys.OWNER_UUID);
        OwnerId itemOwner;
        try {
            itemOwner = owner == null || owner.isNull()
                    ? null
                    : OwnerId.parse(owner.asString().getValue());
        } catch (RuntimeException invalidOwner) {
            return false;
        }
        return !metadata.containsKey(
                TameworkMetadataKeys.COMPANION_PROFILE_ID
        )
                && !metadata.containsKey(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
        )
                && captured != null
                && captured.isBoolean()
                && captured.asBoolean().getValue()
                && role != null
                && role.isString()
                && role.asString().getValue().equalsIgnoreCase(
                recovery.initialIdentity().roleId()
        )
                && Objects.equals(itemOwner, recovery.initialOwner());
    }

    private static boolean compatibleSourceRevision(
            LifecycleRevision source,
            LifecycleRevision expected
    ) {
        return source.equals(expected)
                || source.value() < Long.MAX_VALUE
                && source.value() + 1 == expected.value();
    }

    /** Returns the canonical target world without storing a second placement authority. */
    @Nonnull
    public String targetWorldKey() {
        return placement.worldKey();
    }

    /** Returns this request with the frozen admission result attached. */
    @Nonnull
    public CompanionCaptureReleaseRequest withAdmissionEvidence(
            @Nonnull LifecycleAdmissionEvidence evidence
    ) {
        if (admissionEvidence != null
                && !java.util.Objects.equals(admissionEvidence, evidence)) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence cannot be replaced"
            );
        }
        return new CompanionCaptureReleaseRequest(
                profileId,
                expectedLifecycleRevision,
                sourceSnapshot,
                sourceAlias,
                projection,
                source,
                targetAlias,
                ownerAssignment,
                placement,
                inventoryReceiptKey,
                spawnReceiptKey,
                requestedAtMs,
                legacyRecovery,
                modernRecovery,
                orphanRecovery,
                evidence
        );
    }

    private static void requireAdmissionEvidence(
            LifecycleAdmissionEvidence evidence,
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            CaptureReleaseSourceEvidence source,
            OwnerId ownerAssignment,
            CompanionSpawnPlacement placement,
            CaptureReleaseLegacyRecoveryEvidence legacyRecovery,
            CaptureReleaseOrphanRecoveryEvidence orphanRecovery
    ) {
        if (evidence == null) {
            return;
        }
        if (legacyRecovery != null || orphanRecovery != null) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence requires a canonical capture source"
            );
        }
        if (evidence.status() != LifecycleAdmissionEvidence.Status.MANAGED) {
            return;
        }
        var payload = evidence.payload();
        OwnerId sourceOwner = sourceOwnerFromArtifact(source.sourceArtifact());
        OwnerId targetOwner = ownerAssignment == null
                ? sourceOwner : ownerAssignment;
        String sourceWorld = sourceOwner == null ? null : source.worldKey();
        if (payload == null
                || !payload.profileId().equals(profileId)
                || !java.util.Objects.equals(
                payload.expectedLifecycleRevision(), expectedLifecycleRevision
        )
                || payload.targetLifecycle() != LifecycleState.ACTIVE
                || payload.sourceLifecycle() != LifecycleState.CAPTURED
                || payload.domains().isEmpty()
                || !java.util.Objects.equals(payload.ownerId(), targetOwner)
                || !java.util.Objects.equals(
                payload.ownerWorldKey(),
                targetOwner == null ? null : placement.worldKey()
        )
                || !java.util.Objects.equals(payload.sourceOwnerId(), sourceOwner)
                || !java.util.Objects.equals(payload.sourceWorldKey(), sourceWorld)) {
            throw new IllegalArgumentException(
                    "Capture release lifecycle admission evidence is inconsistent"
            );
        }
    }

    @Nullable
    private static OwnerId sourceOwnerFromArtifact(CapturedArtifact artifact) {
        BsonValue owner = BsonDocument.parse(
                artifact.metadataExtendedJson()
        ).get(TameworkMetadataKeys.OWNER_UUID);
        return owner == null || owner.isNull()
                ? null : OwnerId.parse(owner.asString().getValue());
    }

    private static void requireSourceReceipt(
            CapturedArtifact artifact,
            ProfileId profileId,
            NpcAlias sourceAlias,
            String snapshotId
    ) {
        BsonDocument metadata = BsonDocument.parse(
                artifact.metadataExtendedJson()
        );
        requireStringMetadata(
                metadata,
                TameworkMetadataKeys.TARGET_UUID,
                sourceAlias.toString(),
                "source alias"
        );
        BsonValue profileValue = metadata.get(
                TameworkMetadataKeys.COMPANION_PROFILE_ID
        );
        BsonValue snapshotValue = metadata.get(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
        );
        if (profileValue == null && snapshotValue == null) {
            return;
        }
        if (profileValue == null || snapshotValue == null) {
            throw new IllegalArgumentException(
                    "Captured artifact identity receipt is incomplete"
            );
        }
        requireStringMetadata(
                metadata,
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                snapshotId,
                "capture snapshot"
        );
        requireStringMetadata(
                metadata,
                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                profileId.toString(),
                "companion profile"
        );
    }

    private static void requireReleaseReceipt(
            CapturedArtifact artifact,
            String releaseReceiptKey
    ) {
        BsonDocument metadata = BsonDocument.parse(
                artifact.metadataExtendedJson()
        );
        requireStringMetadata(
                metadata,
                TameworkMetadataKeys.CAPTURE_RELEASE_RECEIPT,
                releaseReceiptKey,
                "release"
        );
    }

    private static void requireStringMetadata(
            BsonDocument metadata,
            String key,
            String expected,
            String label
    ) {
        BsonValue value = metadata.get(key);
        if (value == null || !value.isString()
                || !expected.equals(value.asString().getValue())) {
            throw new IllegalArgumentException(
                    "Captured artifact " + label + " receipt is not exact"
            );
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}

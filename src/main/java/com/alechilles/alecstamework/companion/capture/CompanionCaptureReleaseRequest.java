package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
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
        @Nullable CaptureReleaseModernRecoveryEvidence modernRecovery
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
        if (legacyRecovery != null && modernRecovery != null) {
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
        requireReleaseReceipt(
                source.receiptArtifact(),
                inventoryReceiptKey
        );
        if (inventoryReceiptKey.equals(spawnReceiptKey)) {
            throw new IllegalArgumentException(
                    "Captured-artifact inventory and spawn receipts must be distinct"
            );
        }
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

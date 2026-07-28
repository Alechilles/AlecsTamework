package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Strict classifier for current canonical captured artifacts eligible for managed-coop intake.
 *
 * <p>Released-public alias-only artifacts remain valid for their existing release path, but they
 * do not identify an exact canonical profile and snapshot and therefore cannot enter the
 * captured-item coop operation.</p>
 */
public record CapturedItemCoopArtifactClaim(
        @Nonnull NpcAlias sourceAlias,
        @Nonnull ProfileId profileId,
        @Nonnull SnapshotId captureSnapshotId
) {
    public CapturedItemCoopArtifactClaim {
        if (sourceAlias == null || profileId == null
                || captureSnapshotId == null) {
            throw new IllegalArgumentException(
                    "Complete captured-item coop identity is required"
            );
        }
    }

    /** Parses only the complete current identity shape and rejects receipt-marked artifacts. */
    @Nullable
    public static CapturedItemCoopArtifactClaim parse(
            @Nullable CapturedArtifact artifact
    ) {
        if (artifact == null) {
            return null;
        }
        try {
            BsonDocument metadata = BsonDocument.parse(
                    artifact.metadataExtendedJson()
            );
            if (metadata.containsKey(
                    CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY
            )) {
                return null;
            }
            return new CapturedItemCoopArtifactClaim(
                    new NpcAlias(java.util.UUID.fromString(text(
                            metadata, TameworkMetadataKeys.TARGET_UUID
                    ))),
                    ProfileId.parse(text(
                            metadata,
                            TameworkMetadataKeys.COMPANION_PROFILE_ID
                    )),
                    SnapshotId.parse(text(
                            metadata,
                            TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                    ))
            );
        } catch (RuntimeException invalidEvidence) {
            return null;
        }
    }

    /** Returns whether the artifact is carrying an in-flight coop retirement marker. */
    public static boolean receiptMarked(@Nullable CapturedArtifact artifact) {
        if (artifact == null) {
            return false;
        }
        try {
            return BsonDocument.parse(artifact.metadataExtendedJson())
                    .containsKey(
                            CoopCapturedItemSourceEvidence
                                    .RECEIPT_METADATA_KEY
                    );
        } catch (RuntimeException invalidEvidence) {
            return false;
        }
    }

    private static String text(BsonDocument metadata, String key) {
        BsonValue value = metadata.get(key);
        if (value == null || !value.isString()
                || value.asString().getValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Captured-item coop metadata is invalid: " + key
            );
        }
        return value.asString().getValue();
    }
}

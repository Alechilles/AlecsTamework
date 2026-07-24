package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** Immutable command to replace one live companion with one exact captured artifact. */
public record CompanionCaptureRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nullable OwnerId resultingOwnerId,
        @Nonnull NpcAlias targetAlias,
        @Nonnull String targetWorldKey,
        @Nonnull CompanionSnapshot snapshot,
        @Nonnull CapturedArtifact artifact,
        @Nonnull CaptureSourceEvidence source,
        long requestedAtMs
) {
    public static final SnapshotKind SNAPSHOT_KIND = new SnapshotKind("capture");

    public CompanionCaptureRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || targetAlias == null || snapshot == null
                || artifact == null || source == null) {
            throw new IllegalArgumentException("Complete companion capture request is required");
        }
        targetWorldKey = requireText(targetWorldKey, "Capture target world");
        if (!profileId.equals(snapshot.profileId())
                || !SNAPSHOT_KIND.equals(snapshot.kind())
                || !snapshot.current()
                || !snapshot.sourceLifecycleRevision().equals(
                        expectedLifecycleRevision.next()
                )) {
            throw new IllegalArgumentException(
                    "Capture snapshot must describe the post-prepare lifecycle fence"
            );
        }
        if (!targetWorldKey.equals(source.worldKey())) {
            throw new IllegalArgumentException(
                    "Capture source and target must share one world boundary"
            );
        }
        if (!snapshot.snapshotId().toString().equals(source.receiptKey())) {
            throw new IllegalArgumentException(
                    "Capture source receipt must equal the capture snapshot ID"
            );
        }
        requireArtifactReceipt(artifact, source.receiptKey());
    }

    private static void requireArtifactReceipt(
            CapturedArtifact artifact,
            String expectedReceipt
    ) {
        BsonDocument metadata = BsonDocument.parse(
                artifact.metadataExtendedJson()
        );
        BsonValue receipt = metadata.get(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
        );
        if (receipt == null || !receipt.isString()
                || !expectedReceipt.equals(receipt.asString().getValue())) {
            throw new IllegalArgumentException(
                    "Captured artifact receipt must equal the capture source receipt"
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

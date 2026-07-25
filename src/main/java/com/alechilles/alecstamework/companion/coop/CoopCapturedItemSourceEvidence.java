package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * Exact current captured-artifact evidence for moving one companion into a coop.
 *
 * <p>The source artifact remains canonical capture authority. The receipt artifact is only the
 * exact source value plus the operation receipt, allowing recovery to distinguish an unspent
 * artifact from this operation's durable inventory mutation without restoring a portable
 * persistence envelope.</p>
 */
public record CoopCapturedItemSourceEvidence(
        @Nonnull NpcAlias sourceAlias,
        @Nonnull ProfileId sourceProfileId,
        @Nonnull CompanionSnapshot captureSnapshot,
        @Nonnull UUID actorUuid,
        @Nonnull String sourceWorldKey,
        @Nonnull CoopCapturedItemInventoryPosition inventoryPosition,
        @Nonnull CapturedArtifact sourceArtifact,
        @Nonnull CapturedArtifact receiptArtifact,
        @Nonnull String retirementReceiptKey
) implements CoopCaptureSource {
    public static final String RECEIPT_METADATA_KEY =
            "Tamework.CoopCaptureReceipt";

    public CoopCapturedItemSourceEvidence {
        if (sourceAlias == null || sourceProfileId == null
                || captureSnapshot == null || actorUuid == null
                || inventoryPosition == null || sourceArtifact == null
                || receiptArtifact == null) {
            throw new IllegalArgumentException(
                    "Complete captured-item coop source evidence is required"
            );
        }
        sourceWorldKey = requireText(
                sourceWorldKey, "Captured-item coop source world"
        );
        retirementReceiptKey = requireText(
                retirementReceiptKey, "Captured-item coop retirement receipt"
        );
        requireCurrentCaptureSnapshot(
                sourceProfileId, captureSnapshot
        );
        requireCurrentArtifact(
                sourceArtifact,
                sourceAlias,
                sourceProfileId,
                captureSnapshot
        );
        requireExactReceiptArtifact(
                sourceArtifact, receiptArtifact, retirementReceiptKey
        );
    }

    @Override
    @Nonnull
    public Kind kind() {
        return Kind.CAPTURED_ITEM;
    }

    private static void requireCurrentCaptureSnapshot(
            ProfileId profileId,
            CompanionSnapshot snapshot
    ) {
        if (!profileId.equals(snapshot.profileId())
                || !CompanionCaptureRequest.SNAPSHOT_KIND.equals(
                snapshot.kind()
        )
                || snapshot.payloadVersion()
                != CompanionCaptureRequest.SNAPSHOT_VERSION
                || !snapshot.current()) {
            throw new IllegalArgumentException(
                    "Captured-item coop source requires the exact current capture snapshot"
            );
        }
    }

    private static void requireCurrentArtifact(
            CapturedArtifact artifact,
            NpcAlias alias,
            ProfileId profileId,
            CompanionSnapshot snapshot
    ) {
        BsonDocument metadata = BsonDocument.parse(
                artifact.metadataExtendedJson()
        );
        requireMetadata(
                metadata,
                TameworkMetadataKeys.TARGET_UUID,
                alias.toString(),
                "source alias"
        );
        requireMetadata(
                metadata,
                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                profileId.toString(),
                "source profile"
        );
        requireMetadata(
                metadata,
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                snapshot.snapshotId().toString(),
                "capture snapshot"
        );
        if (metadata.containsKey(RECEIPT_METADATA_KEY)) {
            throw new IllegalArgumentException(
                    "Captured-item coop source already contains a retirement receipt"
            );
        }
    }

    private static void requireExactReceiptArtifact(
            CapturedArtifact source,
            CapturedArtifact receipt,
            String receiptKey
    ) {
        BsonDocument expected = BsonDocument.parse(
                source.metadataExtendedJson()
        );
        expected.put(RECEIPT_METADATA_KEY, new BsonString(receiptKey));
        if (!source.itemId().equals(receipt.itemId())
                || source.quantity() != receipt.quantity()
                || Double.compare(
                source.durability(), receipt.durability()
        ) != 0
                || Double.compare(
                source.maxDurability(), receipt.maxDurability()
        ) != 0
                || !expected.equals(BsonDocument.parse(
                receipt.metadataExtendedJson()
        ))) {
            throw new IllegalArgumentException(
                    "Captured-item coop receipt must be the exact marked source artifact"
            );
        }
    }

    private static void requireMetadata(
            BsonDocument metadata,
            String key,
            String expected,
            String label
    ) {
        BsonValue value = metadata.get(key);
        if (value == null || !value.isString()
                || !expected.equals(value.asString().getValue())) {
            throw new IllegalArgumentException(
                    "Captured-item coop " + label + " is not exact"
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

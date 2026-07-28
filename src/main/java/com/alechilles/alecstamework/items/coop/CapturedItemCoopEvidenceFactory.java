package com.alechilles.alecstamework.items.coop;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bson.BsonDocument;
import org.bson.BsonString;

/**
 * Deterministically freezes one captured-item coop operation request.
 *
 * <p>All identifiers, receipt evidence, and the target snapshot derive from immutable source
 * evidence, so a retry cannot drift when wall-clock time or mutable item assets change.</p>
 */
final class CapturedItemCoopEvidenceFactory {
    private static final String CAPTURE = "captured-item-coop:v1";
    private static final String TARGET_SNAPSHOT =
            "captured-item-coop-snapshot:v1";
    private static final String RETIREMENT_RECEIPT =
            "captured-item-coop-retirement:v1";

    Prepared create(
            CapturedItemCoopAuthor.Source source,
            CoopSlotKey slot,
            CapturedItemCoopArtifactClaim claim,
            CompanionProfileReadModel profile,
            CompanionSnapshot captureSnapshot
    ) {
        String[] parts = intentParts(
                source, slot, claim, profile, captureSnapshot
        );
        OperationId operationId =
                StablePersistenceIds.operationId(CAPTURE, parts);
        String retirementReceipt = StablePersistenceIds.receipt(
                RETIREMENT_RECEIPT, parts
        );
        CoopCapturedItemSourceEvidence evidence =
                new CoopCapturedItemSourceEvidence(
                        claim.sourceAlias(),
                        claim.profileId(),
                        captureSnapshot,
                        source.actorUuid(),
                        source.sourceWorldKey(),
                        source.inventoryPosition(),
                        source.sourceArtifact(),
                        markedArtifact(
                                source.sourceArtifact(),
                                retirementReceipt
                        ),
                        retirementReceipt
                );
        CompanionCoopCaptureRequest request =
                new CompanionCoopCaptureRequest(
                        claim.profileId(),
                        profile.lifecycle().revision(),
                        slot,
                        targetSnapshot(
                                captureSnapshot,
                                slot,
                                new SnapshotId(
                                        StablePersistenceIds.operationId(
                                                TARGET_SNAPSHOT, parts
                                        ).value()
                                ),
                                profile
                        ),
                        evidence,
                        captureSnapshot.createdAtMs()
                );
        return new Prepared(
                operationId,
                StablePersistenceIds.idempotencyKey(CAPTURE, parts),
                request
        );
    }

    private CompanionSnapshot targetSnapshot(
            CompanionSnapshot source,
            CoopSlotKey slot,
            SnapshotId snapshotId,
            CompanionProfileReadModel profile
    ) {
        JsonElement parsed = JsonParser.parseString(source.payloadJson());
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(
                    "Captured-item source snapshot must be an object"
            );
        }
        JsonObject payload = parsed.getAsJsonObject().deepCopy();
        payload.addProperty("coopId", slot.coopId());
        payload.addProperty("residentSlot", slot.residentSlot());
        String json = payload.toString();
        return new CompanionSnapshot(
                snapshotId,
                profile.identity().profileId(),
                CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                CompanionCoopCaptureRequest.SNAPSHOT_VERSION,
                json,
                Sha256Hash.ofUtf8(json),
                profile.lifecycle().revision().next(),
                true,
                source.createdAtMs()
        );
    }

    private CapturedArtifact markedArtifact(
            CapturedArtifact source,
            String receipt
    ) {
        BsonDocument metadata = BsonDocument.parse(
                source.metadataExtendedJson()
        );
        metadata.put(
                CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY,
                new BsonString(receipt)
        );
        return CapturedArtifact.create(
                source.itemId(),
                source.quantity(),
                source.durability(),
                source.maxDurability(),
                metadata.toJson()
        );
    }

    private String[] intentParts(
            CapturedItemCoopAuthor.Source source,
            CoopSlotKey slot,
            CapturedItemCoopArtifactClaim claim,
            CompanionProfileReadModel profile,
            CompanionSnapshot snapshot
    ) {
        return new String[] {
                claim.profileId().toString(),
                claim.sourceAlias().toString(),
                snapshot.snapshotId().toString(),
                Long.toString(profile.lifecycle().revision().value()),
                source.actorUuid().toString(),
                source.sourceWorldKey(),
                source.inventoryPosition().section().name(),
                Integer.toString(source.inventoryPosition().slot()),
                source.sourceArtifact().artifactHash().value(),
                slot.toString()
        };
    }

    record Prepared(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionCoopCaptureRequest request
    ) {
    }
}

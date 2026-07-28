package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.capture.CapturedArtifactJsonCodec;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

/** Backward-compatible typed operation definition for entity or captured-item coop intake. */
public final class CompanionCoopCaptureDefinition
        implements OperationDefinition<CompanionCoopCaptureRequest> {
    public static final CompanionCoopCaptureDefinition INSTANCE =
            new CompanionCoopCaptureDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_coop_capture");

    private CompanionCoopCaptureDefinition() {
    }

    @Override
    public OperationKind kind() {
        return KIND;
    }

    @Override
    public int payloadVersion() {
        return 1;
    }

    @Override
    public Class<CompanionCoopCaptureRequest> payloadType() {
        return CompanionCoopCaptureRequest.class;
    }

    @Override
    public String encode(CompanionCoopCaptureRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        json.addProperty("targetSlot", payload.targetSlot().toString());
        json.add("snapshot", CompanionSnapshotJsonCodec.encode(payload.snapshot()));
        json.add("source", encodeSource(payload.source()));
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    private JsonObject encodeSource(CoopCaptureSource evidence) {
        JsonObject source = new JsonObject();
        source.addProperty("kind", evidence.kind().name());
        source.addProperty("sourceAlias", evidence.sourceAlias().toString());
        source.addProperty("sourceWorldKey", evidence.sourceWorldKey());
        source.addProperty(
                "retirementReceiptKey",
                evidence.retirementReceiptKey()
        );
        if (evidence instanceof CoopCapturedItemSourceEvidence capturedItem) {
            source.addProperty(
                    "sourceProfileId",
                    capturedItem.sourceProfileId().toString()
            );
            source.add(
                    "captureSnapshot",
                    CompanionSnapshotJsonCodec.encode(
                            capturedItem.captureSnapshot()
                    )
            );
            source.addProperty(
                    "actorUuid", capturedItem.actorUuid().toString()
            );
            JsonObject position = new JsonObject();
            position.addProperty(
                    "section",
                    capturedItem.inventoryPosition().section().name()
            );
            position.addProperty(
                    "slot", capturedItem.inventoryPosition().slot()
            );
            source.add("inventoryPosition", position);
            source.add(
                    "sourceArtifact",
                    CapturedArtifactJsonCodec.encode(
                            capturedItem.sourceArtifact()
                    )
            );
            source.add(
                    "receiptArtifact",
                    CapturedArtifactJsonCodec.encode(
                            capturedItem.receiptArtifact()
                    )
            );
        }
        return source;
    }

    private CoopCaptureSource decodeSource(JsonObject source) {
        String encodedKind = source.has("kind")
                ? source.get("kind").getAsString()
                : CoopCaptureSource.Kind.LIVE_ENTITY.name();
        CoopCaptureSource.Kind kind = CoopCaptureSource.Kind.valueOf(
                encodedKind
        );
        if (kind == CoopCaptureSource.Kind.LIVE_ENTITY) {
            return decodeLiveSource(source);
        }
        JsonObject position = source.getAsJsonObject("inventoryPosition");
        return new CoopCapturedItemSourceEvidence(
                NpcAlias.parse(source.get("sourceAlias").getAsString()),
                ProfileId.parse(
                        source.get("sourceProfileId").getAsString()
                ),
                CompanionSnapshotJsonCodec.decode(
                        source.getAsJsonObject("captureSnapshot")
                ),
                UUID.fromString(source.get("actorUuid").getAsString()),
                source.get("sourceWorldKey").getAsString(),
                new CoopCapturedItemInventoryPosition(
                        CoopCapturedItemInventoryPosition.Section.valueOf(
                                position.get("section").getAsString()
                        ),
                        position.get("slot").getAsInt()
                ),
                CapturedArtifactJsonCodec.decode(
                        source.getAsJsonObject("sourceArtifact")
                ),
                CapturedArtifactJsonCodec.decode(
                        source.getAsJsonObject("receiptArtifact")
                ),
                source.get("retirementReceiptKey").getAsString()
        );
    }

    private CoopCaptureSourceEvidence decodeLiveSource(JsonObject source) {
        return new CoopCaptureSourceEvidence(
                NpcAlias.parse(source.get("sourceAlias").getAsString()),
                source.get("sourceWorldKey").getAsString(),
                source.get("retirementReceiptKey").getAsString()
        );
    }

    @Override
    public CompanionCoopCaptureRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        JsonObject source = json.getAsJsonObject("source");
        return new CompanionCoopCaptureRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                CoopSlotKey.parse(json.get("targetSlot").getAsString()),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("snapshot")
                ),
                decodeSource(source),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

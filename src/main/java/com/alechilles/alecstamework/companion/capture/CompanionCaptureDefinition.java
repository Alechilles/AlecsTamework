package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

/** Version-one typed operation definition for live companion capture. */
public final class CompanionCaptureDefinition
        implements OperationDefinition<CompanionCaptureRequest> {
    public static final CompanionCaptureDefinition INSTANCE =
            new CompanionCaptureDefinition();
    public static final OperationKind KIND = new OperationKind("companion_capture");

    private CompanionCaptureDefinition() {
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
    public Class<CompanionCaptureRequest> payloadType() {
        return CompanionCaptureRequest.class;
    }

    @Override
    public String encode(CompanionCaptureRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        nullable(json, "resultingOwnerId", payload.resultingOwnerId());
        json.addProperty("targetAlias", payload.targetAlias().toString());
        json.addProperty("targetWorldKey", payload.targetWorldKey());
        json.add("snapshot", CompanionSnapshotJsonCodec.encode(payload.snapshot()));
        json.add("source", encodeSource(payload.source()));
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CompanionCaptureRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        String owner = nullableText(json, "resultingOwnerId");
        return new CompanionCaptureRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                owner == null ? null : OwnerId.parse(owner),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                json.get("targetWorldKey").getAsString(),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("snapshot")
                ),
                decodeSource(json.getAsJsonObject("source")),
                json.get("requestedAtMs").getAsLong()
        );
    }

    private JsonObject encodeSource(CaptureSourceEvidence source) {
        JsonObject json = new JsonObject();
        json.addProperty("actorUuid", source.actorUuid().toString());
        json.addProperty("worldKey", source.worldKey());
        json.addProperty("slot", source.slot());
        json.addProperty("sourceItemId", source.sourceItemId());
        json.addProperty("quantity", source.quantity());
        json.addProperty("beforeFingerprint", source.beforeFingerprint());
        json.addProperty("afterFingerprint", source.afterFingerprint());
        json.addProperty("receiptKey", source.receiptKey());
        return json;
    }

    private CaptureSourceEvidence decodeSource(JsonObject json) {
        return new CaptureSourceEvidence(
                UUID.fromString(json.get("actorUuid").getAsString()),
                json.get("worldKey").getAsString(),
                json.get("slot").getAsInt(),
                json.get("sourceItemId").getAsString(),
                json.get("quantity").getAsInt(),
                json.get("beforeFingerprint").getAsString(),
                json.get("afterFingerprint").getAsString(),
                json.get("receiptKey").getAsString()
        );
    }

    private void nullable(JsonObject json, String name, Object value) {
        if (value == null) {
            json.add(name, null);
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private String nullableText(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}

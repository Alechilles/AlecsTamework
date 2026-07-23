package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one typed operation definition for live-to-coop capture. */
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
        JsonObject source = new JsonObject();
        source.addProperty("sourceAlias", payload.source().sourceAlias().toString());
        source.addProperty("sourceWorldKey", payload.source().sourceWorldKey());
        source.addProperty(
                "retirementReceiptKey",
                payload.source().retirementReceiptKey()
        );
        json.add("source", source);
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
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
                new CoopCaptureSourceEvidence(
                        NpcAlias.parse(source.get("sourceAlias").getAsString()),
                        source.get("sourceWorldKey").getAsString(),
                        source.get("retirementReceiptKey").getAsString()
                ),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

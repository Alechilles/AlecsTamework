package com.alechilles.alecstamework.companion.dormant;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one typed operation definition shared by death and lost transitions. */
public final class CompanionDormantTransitionDefinition
        implements OperationDefinition<CompanionDormantTransitionRequest> {
    public static final CompanionDormantTransitionDefinition INSTANCE =
            new CompanionDormantTransitionDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_dormant_transition");

    private CompanionDormantTransitionDefinition() {
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
    public Class<CompanionDormantTransitionRequest> payloadType() {
        return CompanionDormantTransitionRequest.class;
    }

    @Override
    public String encode(CompanionDormantTransitionRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        json.add("snapshot", CompanionSnapshotJsonCodec.encode(payload.snapshot()));
        json.add("source", encodeSource(payload.source()));
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CompanionDormantTransitionRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionDormantTransitionRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("snapshot")
                ),
                decodeSource(json.getAsJsonObject("source")),
                json.get("requestedAtMs").getAsLong()
        );
    }

    private JsonObject encodeSource(DormantSourceEvidence source) {
        JsonObject json = new JsonObject();
        json.addProperty("sourceAlias", source.sourceAlias().toString());
        json.addProperty("sourceWorldKey", source.sourceWorldKey());
        json.addProperty("kind", source.kind().name());
        json.addProperty(
                "observedGeneration",
                source.observedGeneration().value()
        );
        json.addProperty("receiptKey", source.receiptKey());
        json.addProperty("observedAtMs", source.observedAtMs());
        return json;
    }

    private DormantSourceEvidence decodeSource(JsonObject json) {
        return new DormantSourceEvidence(
                NpcAlias.parse(json.get("sourceAlias").getAsString()),
                json.get("sourceWorldKey").getAsString(),
                DormantSourceEvidence.Kind.valueOf(
                        json.get("kind").getAsString()
                ),
                new ReconciliationGeneration(
                        json.get("observedGeneration").getAsLong()
                ),
                json.get("receiptKey").getAsString(),
                json.get("observedAtMs").getAsLong()
        );
    }
}

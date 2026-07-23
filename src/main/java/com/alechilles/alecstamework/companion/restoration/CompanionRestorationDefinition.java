package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one typed operation definition for death and lost restoration. */
public final class CompanionRestorationDefinition
        implements OperationDefinition<CompanionRestorationRequest> {
    public static final CompanionRestorationDefinition INSTANCE =
            new CompanionRestorationDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_restoration");

    private CompanionRestorationDefinition() {
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
    public Class<CompanionRestorationRequest> payloadType() {
        return CompanionRestorationRequest.class;
    }

    @Override
    public String encode(CompanionRestorationRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        json.addProperty("sourceState", payload.sourceState().name());
        json.add(
                "sourceSnapshot",
                CompanionSnapshotJsonCodec.encode(payload.sourceSnapshot())
        );
        json.addProperty("targetAlias", payload.targetAlias().toString());
        json.addProperty("targetWorldKey", payload.targetWorldKey());
        json.addProperty("spawnReceiptKey", payload.spawnReceiptKey());
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CompanionRestorationRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionRestorationRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                LifecycleState.valueOf(json.get("sourceState").getAsString()),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("sourceSnapshot")
                ),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                json.get("targetWorldKey").getAsString(),
                json.get("spawnReceiptKey").getAsString(),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

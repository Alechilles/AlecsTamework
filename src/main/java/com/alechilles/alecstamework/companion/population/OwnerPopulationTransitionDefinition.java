package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one operation definition for owner and owner-world transitions. */
public final class OwnerPopulationTransitionDefinition
        implements OperationDefinition<OwnerPopulationTransitionRequest> {
    public static final OwnerPopulationTransitionDefinition INSTANCE =
            new OwnerPopulationTransitionDefinition();
    public static final OperationKind KIND =
            new OperationKind("owner_population_transition");

    private OwnerPopulationTransitionDefinition() {
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
    public Class<OwnerPopulationTransitionRequest> payloadType() {
        return OwnerPopulationTransitionRequest.class;
    }

    @Override
    public String encode(OwnerPopulationTransitionRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        nullable(json, "expectedOwnerId", payload.expectedOwnerId());
        nullable(
                json,
                "expectedOwnerWorldKey",
                payload.expectedOwnerWorldKey()
        );
        nullable(json, "targetOwnerId", payload.targetOwnerId());
        nullable(
                json,
                "targetOwnerWorldKey",
                payload.targetOwnerWorldKey()
        );
        json.addProperty("globalLimit", payload.globalLimit());
        json.addProperty("perWorldLimit", payload.perWorldLimit());
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public OwnerPopulationTransitionRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        String expectedOwner = text(json, "expectedOwnerId");
        String targetOwner = text(json, "targetOwnerId");
        return new OwnerPopulationTransitionRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                expectedOwner == null
                        ? null
                        : OwnerId.parse(expectedOwner),
                text(json, "expectedOwnerWorldKey"),
                targetOwner == null ? null : OwnerId.parse(targetOwner),
                text(json, "targetOwnerWorldKey"),
                json.get("globalLimit").getAsInt(),
                json.get("perWorldLimit").getAsInt(),
                json.get("requestedAtMs").getAsLong()
        );
    }

    private void nullable(JsonObject json, String name, Object value) {
        if (value == null) {
            json.add(name, null);
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }
}

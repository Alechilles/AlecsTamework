package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one definition for a generic death-snapshot readiness mutation. */
public final class ReviveReadyDefinition implements OperationDefinition<ReviveReadyRequest> {
    public static final ReviveReadyDefinition INSTANCE = new ReviveReadyDefinition();
    public static final OperationKind KIND = new OperationKind("companion_revive_ready");
    private ReviveReadyDefinition() { }
    @Override public OperationKind kind() { return KIND; }
    @Override public int payloadVersion() { return 1; }
    @Override public Class<ReviveReadyRequest> payloadType() { return ReviveReadyRequest.class; }
    @Override public String encode(ReviveReadyRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", request.profileId().toString());
        json.addProperty("requestedAtMs", request.requestedAtMs());
        return json.toString();
    }
    @Override public ReviveReadyRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new ReviveReadyRequest(ProfileId.parse(json.get("profileId").getAsString()),
                json.get("requestedAtMs").getAsLong());
    }
}

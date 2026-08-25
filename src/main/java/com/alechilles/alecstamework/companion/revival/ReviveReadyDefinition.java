package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Version-two definition for an owner-authorized death-snapshot readiness mutation.
 *
 * <p>The unsafe version-one payload existed only on an unreleased local branch.
 * It had no owner evidence, so this first releasable definition does not accept it.</p>
 */
public final class ReviveReadyDefinition implements OperationDefinition<ReviveReadyRequest> {
    public static final ReviveReadyDefinition INSTANCE = new ReviveReadyDefinition();
    public static final OperationKind KIND = new OperationKind("companion_revive_ready");
    private ReviveReadyDefinition() { }
    @Override public OperationKind kind() { return KIND; }
    @Override public int payloadVersion() { return 2; }
    @Override public Class<ReviveReadyRequest> payloadType() { return ReviveReadyRequest.class; }
    @Override public String encode(ReviveReadyRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", request.profileId().toString());
        json.addProperty("ownerId", request.ownerId().toString());
        json.addProperty("requestedAtMs", request.requestedAtMs());
        return json.toString();
    }
    @Override public ReviveReadyRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new ReviveReadyRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                OwnerId.parse(json.get("ownerId").getAsString()),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

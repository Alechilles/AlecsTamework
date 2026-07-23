package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one operation definition for all profile extension puts and deletes. */
public final class ProfileExtensionMutationDefinition
        implements OperationDefinition<ProfileExtensionMutation> {
    public static final ProfileExtensionMutationDefinition INSTANCE =
            new ProfileExtensionMutationDefinition();
    public static final OperationKind KIND =
            new OperationKind("profile_extension_mutation");

    private ProfileExtensionMutationDefinition() {
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
    public Class<ProfileExtensionMutation> payloadType() {
        return ProfileExtensionMutation.class;
    }

    @Override
    public String encode(ProfileExtensionMutation payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.key().profileId().toString());
        json.addProperty("namespace", payload.key().namespace());
        json.addProperty("dataKey", payload.key().dataKey());
        json.addProperty("action", payload.action().name());
        if (payload.expectedRevision() == null) {
            json.add("expectedRevision", null);
        } else {
            json.addProperty("expectedRevision", payload.expectedRevision());
        }
        if (payload.jsonPayload() == null) {
            json.add("jsonPayload", null);
        } else {
            json.addProperty("jsonPayload", payload.jsonPayload());
        }
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public ProfileExtensionMutation decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        JsonElement expected = json.get("expectedRevision");
        JsonElement payload = json.get("jsonPayload");
        return new ProfileExtensionMutation(
                new ProfileExtensionKey(
                        ProfileId.parse(json.get("profileId").getAsString()),
                        json.get("namespace").getAsString(),
                        json.get("dataKey").getAsString()
                ),
                ProfileExtensionMutationAction.valueOf(json.get("action").getAsString()),
                expected == null || expected.isJsonNull() ? null : expected.getAsLong(),
                payload == null || payload.isJsonNull() ? null : payload.getAsString(),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

package com.alechilles.alecstamework.companion.identity;

import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one operation definition for a pre-leased, live-confirmed alias rotation. */
public final class CompanionAliasRotationDefinition
        implements OperationDefinition<CompanionAliasRotation> {
    public static final CompanionAliasRotationDefinition INSTANCE =
            new CompanionAliasRotationDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_alias_rotation");

    private CompanionAliasRotationDefinition() {
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
    public Class<CompanionAliasRotation> payloadType() {
        return CompanionAliasRotation.class;
    }

    @Override
    public String encode(CompanionAliasRotation payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty("targetAlias", payload.targetAlias().toString());
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CompanionAliasRotation decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionAliasRotation(
                ProfileId.parse(json.get("profileId").getAsString()),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionIdentityJsonCodec;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Version-one operation definition for profile creation and metadata/tool-link updates. */
public final class CompanionProfileMutationDefinition
        implements OperationDefinition<CompanionProfileMutation> {
    public static final CompanionProfileMutationDefinition INSTANCE =
            new CompanionProfileMutationDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_profile_mutation");

    private CompanionProfileMutationDefinition() {
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
    public Class<CompanionProfileMutation> payloadType() {
        return CompanionProfileMutation.class;
    }

    @Override
    public String encode(CompanionProfileMutation payload) {
        JsonObject json = new JsonObject();
        if (payload instanceof CompanionProfileMutation.Create create) {
            json.addProperty("action", "CREATE");
            json.add("identity", CompanionIdentityJsonCodec.encode(create.identity()));
            json.add("lifecycle", CompanionLifecycleJsonCodec.encode(create.lifecycle()));
            json.add("toolLinks", encodeLinks(create.toolLinks()));
        } else if (payload instanceof CompanionProfileMutation.AdoptLive adoption) {
            json.addProperty("action", "ADOPT_LIVE");
            json.add("identity", CompanionIdentityJsonCodec.encode(adoption.identity()));
            json.addProperty("alias", adoption.alias().toString());
            if (adoption.ownerId() == null) {
                json.add("ownerId", JsonNull.INSTANCE);
            } else {
                json.addProperty("ownerId", adoption.ownerId().toString());
            }
            json.addProperty("worldKey", adoption.worldKey());
            json.add("toolLinks", encodeLinks(adoption.toolLinks()));
        } else if (payload instanceof CompanionProfileMutation.Update update) {
            json.addProperty("action", "UPDATE");
            json.add("identity", CompanionIdentityJsonCodec.encode(update.nextIdentity()));
            json.addProperty(
                    "expectedMetadataRevision",
                    update.expectedMetadataRevision()
            );
            json.add("toolLinks", encodeLinks(update.toolLinks()));
        } else {
            throw new IllegalArgumentException("Unknown companion profile mutation");
        }
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CompanionProfileMutation decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        CompanionIdentity identity =
                CompanionIdentityJsonCodec.decode(json.getAsJsonObject("identity"));
        List<CompanionToolLink> links = decodeLinks(json.getAsJsonArray("toolLinks"));
        long requestedAtMs = json.get("requestedAtMs").getAsLong();
        return switch (json.get("action").getAsString()) {
            case "CREATE" -> new CompanionProfileMutation.Create(
                    identity,
                    CompanionLifecycleJsonCodec.decode(
                            json.getAsJsonObject("lifecycle")
                    ),
                    links,
                    requestedAtMs
            );
            case "ADOPT_LIVE" -> new CompanionProfileMutation.AdoptLive(
                    identity,
                    NpcAlias.parse(json.get("alias").getAsString()),
                    decodeOwner(json),
                    json.get("worldKey").getAsString(),
                    links,
                    requestedAtMs
            );
            case "UPDATE" -> new CompanionProfileMutation.Update(
                    identity,
                    json.get("expectedMetadataRevision").getAsLong(),
                    links,
                    requestedAtMs
            );
            default -> throw new IllegalArgumentException(
                    "companion_profile_mutation_action_unknown"
            );
        };
    }

    private JsonArray encodeLinks(List<CompanionToolLink> links) {
        JsonArray json = new JsonArray();
        for (CompanionToolLink link : links) {
            json.add(CompanionIdentityJsonCodec.encodeToolLink(link));
        }
        return json;
    }

    private List<CompanionToolLink> decodeLinks(JsonArray json) {
        ArrayList<CompanionToolLink> links = new ArrayList<>();
        for (int index = 0; index < json.size(); index++) {
            links.add(CompanionIdentityJsonCodec.decodeToolLink(
                    json.get(index).getAsJsonObject()
            ));
        }
        return List.copyOf(links);
    }

    private OwnerId decodeOwner(JsonObject json) {
        return !json.has("ownerId") || json.get("ownerId").isJsonNull()
                ? null
                : OwnerId.parse(json.get("ownerId").getAsString());
    }
}

package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionIdentityJsonCodec;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleJsonCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
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
        JsonObject json;
        if (payload instanceof CompanionProfileMutation.Create create) {
            json = encodeCreate(create);
        } else if (payload instanceof CompanionProfileMutation.AdoptLive adoption) {
            json = encodeAdoption(adoption);
        } else if (payload instanceof
                CompanionProfileMutation.ReconcileLoaded reconciliation) {
            json = encodeReconciliation(reconciliation);
        } else if (payload instanceof CompanionProfileMutation.Update update) {
            json = encodeUpdate(update);
        } else {
            throw new IllegalArgumentException("Unknown companion profile mutation");
        }
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CompanionProfileMutation decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        long requestedAtMs = json.get("requestedAtMs").getAsLong();
        return switch (json.get("action").getAsString()) {
            case "CREATE" -> decodeCreate(json, requestedAtMs);
            case "ADOPT_LIVE" -> decodeAdoption(json, requestedAtMs);
            case "RECONCILE_LOADED" ->
                    decodeReconciliation(json, requestedAtMs);
            case "UPDATE" -> decodeUpdate(json, requestedAtMs);
            default -> throw new IllegalArgumentException(
                    "companion_profile_mutation_action_unknown"
            );
        };
    }

    private JsonObject encodeCreate(CompanionProfileMutation.Create create) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "CREATE");
        json.add("identity", CompanionIdentityJsonCodec.encode(create.identity()));
        json.add("lifecycle", CompanionLifecycleJsonCodec.encode(create.lifecycle()));
        json.add("toolLinks", encodeLinks(create.toolLinks()));
        return json;
    }

    private JsonObject encodeAdoption(
            CompanionProfileMutation.AdoptLive adoption
    ) {
        JsonObject json = new JsonObject();
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
        return json;
    }

    private JsonObject encodeReconciliation(
            CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "RECONCILE_LOADED");
        json.addProperty("profileId", reconciliation.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                reconciliation.expectedLifecycleRevision().value()
        );
        json.addProperty(
                "expectedReconciliationGeneration",
                reconciliation.expectedReconciliationGeneration().value()
        );
        json.addProperty(
                "expectedCurrentAlias",
                reconciliation.expectedCurrentAlias().toString()
        );
        json.addProperty(
                "observedAlias",
                reconciliation.observedAlias().toString()
        );
        json.addProperty("worldKey", reconciliation.worldKey());
        return json;
    }

    private JsonObject encodeUpdate(CompanionProfileMutation.Update update) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "UPDATE");
        json.add("identity", CompanionIdentityJsonCodec.encode(update.nextIdentity()));
        json.addProperty(
                "expectedMetadataRevision",
                update.expectedMetadataRevision()
        );
        json.add("toolLinks", encodeLinks(update.toolLinks()));
        return json;
    }

    private CompanionProfileMutation decodeCreate(
            JsonObject json,
            long requestedAtMs
    ) {
        return new CompanionProfileMutation.Create(
                decodeIdentity(json),
                CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("lifecycle")
                ),
                decodeLinks(json.getAsJsonArray("toolLinks")),
                requestedAtMs
        );
    }

    private CompanionProfileMutation decodeAdoption(
            JsonObject json,
            long requestedAtMs
    ) {
        return new CompanionProfileMutation.AdoptLive(
                decodeIdentity(json),
                NpcAlias.parse(json.get("alias").getAsString()),
                decodeOwner(json),
                json.get("worldKey").getAsString(),
                decodeLinks(json.getAsJsonArray("toolLinks")),
                requestedAtMs
        );
    }

    private CompanionProfileMutation decodeReconciliation(
            JsonObject json,
            long requestedAtMs
    ) {
        return new CompanionProfileMutation.ReconcileLoaded(
                com.alechilles.alecstamework.companion.identity.ProfileId.parse(
                        json.get("profileId").getAsString()
                ),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                new ReconciliationGeneration(
                        json.get("expectedReconciliationGeneration").getAsLong()
                ),
                NpcAlias.parse(json.get("expectedCurrentAlias").getAsString()),
                NpcAlias.parse(json.get("observedAlias").getAsString()),
                json.get("worldKey").getAsString(),
                requestedAtMs
        );
    }

    private CompanionProfileMutation decodeUpdate(
            JsonObject json,
            long requestedAtMs
    ) {
        return new CompanionProfileMutation.Update(
                decodeIdentity(json),
                json.get("expectedMetadataRevision").getAsLong(),
                decodeLinks(json.getAsJsonArray("toolLinks")),
                requestedAtMs
        );
    }

    private CompanionIdentity decodeIdentity(JsonObject json) {
        return CompanionIdentityJsonCodec.decode(
                json.getAsJsonObject("identity")
        );
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

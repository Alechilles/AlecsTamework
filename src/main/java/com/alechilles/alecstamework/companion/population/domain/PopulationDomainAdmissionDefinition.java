package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation.DomainInput;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Version-one codec for the staged named-domain admission operation. */
public final class PopulationDomainAdmissionDefinition
        implements OperationDefinition<PopulationDomainAdmissionOperation.Payload> {
    public static final PopulationDomainAdmissionDefinition INSTANCE =
            new PopulationDomainAdmissionDefinition();
    public static final OperationKind KIND =
            new OperationKind("population_domain_admission");

    private PopulationDomainAdmissionDefinition() {
    }

    @Override
    public OperationKind kind() {
        return KIND;
    }

    @Override
    public int payloadVersion() {
        return 1;
    }

    /** A live claim may have spawned a companion; unknown outcomes require containment. */
    @Override
    public boolean allowsUnknownLiveReverification(
            com.alechilles.alecstamework.persistence.operation.OperationEnvelope operation
    ) {
        return false;
    }

    @Override
    public Class<PopulationDomainAdmissionOperation.Payload> payloadType() {
        return PopulationDomainAdmissionOperation.Payload.class;
    }

    @Override
    public String encode(PopulationDomainAdmissionOperation.Payload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Admission payload is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("reservationId", payload.reservationId().toString());
        json.addProperty("profileId", payload.profileId().toString());
        nullable(json, "ownerId", payload.ownerId());
        nullable(json, "expectedLifecycleRevision", payload.expectedLifecycleRevision());
        nullable(json, "ownerWorldKey", payload.ownerWorldKey());
        nullable(json, "sourceOwnerId", payload.sourceOwnerId());
        nullable(json, "sourceWorldKey", payload.sourceWorldKey());
        nullable(json, "sourceLifecycle", payload.sourceLifecycle());
        json.addProperty("targetLifecycle", payload.targetLifecycle().name());
        json.addProperty("familyGroupId", payload.familyGroupId());
        json.addProperty("providerId", payload.providerId());
        json.addProperty("providerContractVersion", payload.providerContractVersion());
        json.addProperty("providerGenerationToken", payload.providerGenerationToken());
        json.addProperty("providerSnapshotRevision", payload.providerSnapshotRevision());
        json.addProperty("managedConfigRevision", payload.managedConfigRevision());
        json.addProperty("expiresAtMs", payload.expiresAtMs());
        json.addProperty("requestedCount", payload.requestedCount());
        json.addProperty("createdAtMs", payload.createdAtMs());
        JsonArray domains = new JsonArray();
        for (DomainInput input : payload.domains()) {
            JsonObject row = new JsonObject();
            row.addProperty("domainId", input.domainId());
            row.addProperty("scope", input.scope().name());
            nullable(row, "worldKey", input.worldKey());
            row.addProperty("ownedDelta", input.ownedDelta());
            row.addProperty("deployableDelta", input.deployableDelta());
            row.addProperty("weight", input.weight());
            row.addProperty("maxOwned", input.maxOwned());
            row.addProperty("maxDeployable", input.maxDeployable());
            row.addProperty("policyRevision", input.policyRevision());
            domains.add(row);
        }
        json.add("domains", domains);
        JsonArray children = new JsonArray();
        for (UUID child : payload.provisionalChildIds()) {
            children.add(child.toString());
        }
        json.add("provisionalChildIds", children);
        return json.toString();
    }

    @Override
    public PopulationDomainAdmissionOperation.Payload decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        ArrayList<DomainInput> domains = new ArrayList<>();
        for (JsonElement item : json.getAsJsonArray("domains")) {
            JsonObject row = item.getAsJsonObject();
            domains.add(new DomainInput(
                    row.get("domainId").getAsString(),
                    PopulationDomainScope.valueOf(row.get("scope").getAsString()),
                    text(row, "worldKey"),
                    row.get("ownedDelta").getAsInt(),
                    row.get("deployableDelta").getAsInt(),
                    row.get("weight").getAsInt(),
                    row.get("maxOwned").getAsInt(),
                    row.get("maxDeployable").getAsInt(),
                    row.get("policyRevision").getAsLong()
            ));
        }
        ArrayList<UUID> children = new ArrayList<>();
        JsonArray childArray = json.getAsJsonArray("provisionalChildIds");
        if (childArray != null) {
            for (JsonElement child : childArray) {
                children.add(UUID.fromString(child.getAsString()));
            }
        }
        String owner = text(json, "ownerId");
        String sourceOwner = text(json, "sourceOwnerId");
        String sourceLifecycle = text(json, "sourceLifecycle");
        String targetLifecycle = text(json, "targetLifecycle");
        String familyGroupId = text(json, "familyGroupId");
        String revision = text(json, "expectedLifecycleRevision");
        return new PopulationDomainAdmissionOperation.Payload(
                UUID.fromString(json.get("reservationId").getAsString()),
                ProfileId.parse(json.get("profileId").getAsString()),
                owner == null ? null : OwnerId.parse(owner),
                revision == null ? null : new LifecycleRevision(Long.parseLong(revision)),
                text(json, "ownerWorldKey"),
                sourceOwner == null ? null : OwnerId.parse(sourceOwner),
                text(json, "sourceWorldKey"),
                sourceLifecycle == null ? null : LifecycleState.valueOf(sourceLifecycle),
                targetLifecycle == null ? LifecycleState.ACTIVE
                        : LifecycleState.valueOf(targetLifecycle),
                familyGroupId == null ? "legacy-unknown" : familyGroupId,
                json.get("providerId").getAsString(),
                json.get("providerContractVersion").getAsInt(),
                json.get("providerGenerationToken").getAsString(),
                json.get("providerSnapshotRevision").getAsLong(),
                json.get("managedConfigRevision").getAsLong(),
                json.get("expiresAtMs").getAsLong(),
                json.get("requestedCount").getAsInt(),
                domains,
                children,
                json.get("createdAtMs").getAsLong()
        );
    }

    private void nullable(JsonObject json, String name, Object value) {
        if (value == null) {
            json.add(name, null);
        } else if (value instanceof LifecycleRevision revision) {
            json.addProperty(name, revision.value());
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}

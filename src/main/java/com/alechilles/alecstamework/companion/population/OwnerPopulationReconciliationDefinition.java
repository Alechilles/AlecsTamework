package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one operation definition for sealed owner-population reconciliation. */
public final class OwnerPopulationReconciliationDefinition
        implements OperationDefinition<OwnerPopulationReconciliationRequest> {
    public static final OwnerPopulationReconciliationDefinition INSTANCE =
            new OwnerPopulationReconciliationDefinition();
    public static final OperationKind KIND =
            new OperationKind("owner_population_reconciliation");

    private OwnerPopulationReconciliationDefinition() {
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
    public Class<OwnerPopulationReconciliationRequest> payloadType() {
        return OwnerPopulationReconciliationRequest.class;
    }

    @Override
    public String encode(OwnerPopulationReconciliationRequest payload) {
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
        OwnerPopulationEvidenceClaim evidence = payload.evidence();
        json.addProperty("claimKind", evidence.kind().name());
        json.addProperty("bootId", evidence.bootId());
        json.addProperty("worldKey", evidence.worldKey());
        json.addProperty("generation", evidence.generation().value());
        nullable(json, "source", evidence.source());
        nullable(json, "observedOwnerId", evidence.observedOwnerId());
        nullable(
                json,
                "observedOwnerWorldKey",
                evidence.observedOwnerWorldKey()
        );
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public OwnerPopulationReconciliationRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        String source = text(json, "source");
        String owner = text(json, "observedOwnerId");
        String expectedOwner = text(json, "expectedOwnerId");
        OwnerPopulationEvidenceClaim evidence =
                new OwnerPopulationEvidenceClaim(
                        OwnerPopulationEvidenceClaim.Kind.valueOf(
                                json.get("claimKind").getAsString()
                        ),
                        json.get("bootId").getAsString(),
                        json.get("worldKey").getAsString(),
                        new ReconciliationGeneration(
                                json.get("generation").getAsLong()
                        ),
                        source == null
                                ? null
                                : PopulationEvidenceBatch.Source.valueOf(
                                        source
                                ),
                        owner == null ? null : OwnerId.parse(owner),
                        text(json, "observedOwnerWorldKey")
                );
        return new OwnerPopulationReconciliationRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                expectedOwner == null
                        ? null
                        : OwnerId.parse(expectedOwner),
                text(json, "expectedOwnerWorldKey"),
                evidence,
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


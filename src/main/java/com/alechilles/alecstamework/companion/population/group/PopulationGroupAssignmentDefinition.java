package com.alechilles.alecstamework.companion.population.group;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;

/** Version-one operation definition for complete population-group assignment. */
public final class PopulationGroupAssignmentDefinition
        implements OperationDefinition<PopulationGroupAssignmentRequest> {
    public static final PopulationGroupAssignmentDefinition INSTANCE =
            new PopulationGroupAssignmentDefinition();
    public static final OperationKind KIND =
            new OperationKind("population_group_assignment");

    private PopulationGroupAssignmentDefinition() {
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
    public Class<PopulationGroupAssignmentRequest> payloadType() {
        return PopulationGroupAssignmentRequest.class;
    }

    @Override
    public String encode(PopulationGroupAssignmentRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedMetadataRevision",
                payload.expectedMetadataRevision()
        );
        nullable(json, "expectedRoleId", payload.expectedRoleId());
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
        if (payload.expectedAssignmentRevision() == null) {
            json.add("expectedAssignmentRevision", null);
        } else {
            json.addProperty(
                    "expectedAssignmentRevision",
                    payload.expectedAssignmentRevision()
            );
        }
        json.addProperty("policyRevision", payload.policyRevision());
        JsonArray policies = new JsonArray();
        for (PopulationGroupPolicy policy : payload.policies()) {
            JsonObject row = new JsonObject();
            row.addProperty("groupId", policy.groupId());
            row.addProperty("scope", policy.scope().name());
            row.addProperty(
                    "maxOwnedPerOwner",
                    policy.maxOwnedPerOwner()
            );
            row.addProperty(
                    "maxActivePerOwner",
                    policy.maxActivePerOwner()
            );
            row.addProperty(
                    "policyRevision",
                    policy.policyRevision()
            );
            policies.add(row);
        }
        json.add("policies", policies);
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public PopulationGroupAssignmentRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        ArrayList<PopulationGroupPolicy> policies = new ArrayList<>();
        for (JsonElement item : json.getAsJsonArray("policies")) {
            JsonObject row = item.getAsJsonObject();
            policies.add(new PopulationGroupPolicy(
                    row.get("groupId").getAsString(),
                    PopulationGroupScope.valueOf(
                            row.get("scope").getAsString()
                    ),
                    row.get("maxOwnedPerOwner").getAsInt(),
                    row.get("maxActivePerOwner").getAsInt(),
                    row.get("policyRevision").getAsLong()
            ));
        }
        String owner = text(json, "expectedOwnerId");
        JsonElement assignment = json.get("expectedAssignmentRevision");
        return new PopulationGroupAssignmentRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                json.get("expectedMetadataRevision").getAsLong(),
                text(json, "expectedRoleId"),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                owner == null ? null : OwnerId.parse(owner),
                text(json, "expectedOwnerWorldKey"),
                assignment == null || assignment.isJsonNull()
                        ? null
                        : assignment.getAsLong(),
                json.get("policyRevision").getAsLong(),
                policies,
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


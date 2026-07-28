package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraftJsonCodec;
import com.alechilles.alecstamework.companion.identity.CompanionIdentityJsonCodec;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.UUID;

/** Version-one shared operation definition for dormant provisioning grants. */
public final class CompanionProvisioningDefinition
        implements OperationDefinition<CompanionProvisioningRequest> {
    public static final CompanionProvisioningDefinition INSTANCE =
            new CompanionProvisioningDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_provisioning");

    private CompanionProvisioningDefinition() {
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
    public Class<CompanionProvisioningRequest> payloadType() {
        return CompanionProvisioningRequest.class;
    }

    @Override
    public String encode(CompanionProvisioningRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty(
                "callerNamespace",
                payload.origin().callerNamespace()
        );
        json.addProperty("callerKey", payload.origin().callerKey());
        nullable(json, "correlationId", payload.correlationId());
        json.add(
                "identity",
                CompanionIdentityJsonCodec.encode(payload.identity())
        );
        json.add(
                "lifecycle",
                CompanionLifecycleJsonCodec.encode(payload.lifecycle())
        );
        json.add(
                "groupAssignment",
                PopulationGroupAssignmentJsonCodec.encode(
                        payload.groupAssignment()
                )
        );
        JsonArray policies = new JsonArray();
        for (PopulationGroupPolicy policy : payload.groupPolicies()) {
            JsonObject row = new JsonObject();
            row.addProperty("groupId", policy.groupId());
            row.addProperty("scope", policy.scope().name());
            row.addProperty(
                    "maxOwnedPerOwner", policy.maxOwnedPerOwner()
            );
            row.addProperty(
                    "maxActivePerOwner", policy.maxActivePerOwner()
            );
            row.addProperty(
                    "policyRevision", policy.policyRevision()
            );
            policies.add(row);
        }
        json.add("groupPolicies", policies);
        json.addProperty(
                "globalOwnerLimit", payload.globalOwnerLimit()
        );
        json.addProperty(
                "perWorldOwnerLimit", payload.perWorldOwnerLimit()
        );
        json.add(
                "commandMembership",
                payload.commandMembership() == null
                        ? null
                        : CommandRosterMembershipDraftJsonCodec.encode(
                                payload.commandMembership()
                        )
        );
        nullable(
                json,
                "expectedCommandRosterRevision",
                payload.expectedCommandRosterRevision()
        );
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CompanionProvisioningRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        ProvisioningOrigin origin = new ProvisioningOrigin(
                json.get("callerNamespace").getAsString(),
                json.get("callerKey").getAsString()
        );
        ArrayList<PopulationGroupPolicy> policies =
                new ArrayList<>();
        for (JsonElement item : json.getAsJsonArray("groupPolicies")) {
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
        JsonElement command = json.get("commandMembership");
        JsonElement correlation = json.get("correlationId");
        CommandRosterMembershipDraft membership =
                command == null || command.isJsonNull()
                        ? null
                        : CommandRosterMembershipDraftJsonCodec.decode(
                                command.getAsJsonObject()
                        );
        return new CompanionProvisioningRequest(
                origin,
                correlation == null || correlation.isJsonNull()
                        ? null
                        : UUID.fromString(correlation.getAsString()),
                CompanionIdentityJsonCodec.decode(
                        json.getAsJsonObject("identity")
                ),
                CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("lifecycle")
                ),
                PopulationGroupAssignmentJsonCodec.decode(
                        json.getAsJsonObject("groupAssignment")
                ),
                policies,
                json.get("globalOwnerLimit").getAsInt(),
                json.get("perWorldOwnerLimit").getAsInt(),
                membership,
                nullableLong(json, "expectedCommandRosterRevision"),
                json.get("requestedAtMs").getAsLong()
        );
    }

    private void nullable(
            JsonObject json,
            String name,
            Object value
    ) {
        if (value == null) {
            json.add(name, null);
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private Long nullableLong(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsLong();
    }
}


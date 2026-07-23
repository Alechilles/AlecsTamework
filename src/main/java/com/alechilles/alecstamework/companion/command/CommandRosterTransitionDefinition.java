package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;

/** Version-one operation definition for command roster lifecycle transitions. */
public final class CommandRosterTransitionDefinition
        implements OperationDefinition<CommandRosterTransitionRequest> {
    public static final CommandRosterTransitionDefinition INSTANCE =
            new CommandRosterTransitionDefinition();
    public static final OperationKind KIND =
            new OperationKind("command_roster_transition");

    private CommandRosterTransitionDefinition() {
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
    public Class<CommandRosterTransitionRequest> payloadType() {
        return CommandRosterTransitionRequest.class;
    }

    @Override
    public String encode(CommandRosterTransitionRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty(
                "ownerId", payload.familyKey().ownerId().toString()
        );
        json.addProperty("familyId", payload.familyKey().familyId());
        json.addProperty("slotId", payload.slotId().toString());
        json.addProperty(
                "expectedMembershipRevision",
                payload.expectedMembershipRevision()
        );
        PopulationGroupTransitionAdmissionRequest admission =
                payload.groupAdmission();
        json.add(
                "before",
                CompanionLifecycleJsonCodec.encode(admission.before())
        );
        json.add(
                "after",
                CompanionLifecycleJsonCodec.encode(admission.after())
        );
        json.addProperty(
                "expectedAssignmentRevision",
                admission.expectedAssignmentRevision()
        );
        json.addProperty(
                "expectedPolicyRevision",
                admission.expectedPolicyRevision()
        );
        JsonArray policies = new JsonArray();
        for (PopulationGroupPolicy policy : admission.policies()) {
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
        json.addProperty(
                "requestedAtMs", admission.requestedAtMs()
        );
        return json.toString();
    }

    @Override
    public CommandRosterTransitionRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        ArrayList<PopulationGroupPolicy> policies = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("policies")) {
            JsonObject row = element.getAsJsonObject();
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
        return new CommandRosterTransitionRequest(
                new CommandFamilyKey(
                        OwnerId.parse(
                                json.get("ownerId").getAsString()
                        ),
                        json.get("familyId").getAsString()
                ),
                CommandRosterSlotId.parse(
                        json.get("slotId").getAsString()
                ),
                json.get("expectedMembershipRevision").getAsLong(),
                new PopulationGroupTransitionAdmissionRequest(
                        CompanionLifecycleJsonCodec.decode(
                                json.getAsJsonObject("before")
                        ),
                        CompanionLifecycleJsonCodec.decode(
                                json.getAsJsonObject("after")
                        ),
                        json.get(
                                "expectedAssignmentRevision"
                        ).getAsLong(),
                        json.get("expectedPolicyRevision").getAsLong(),
                        policies,
                        json.get("requestedAtMs").getAsLong()
                )
        );
    }
}

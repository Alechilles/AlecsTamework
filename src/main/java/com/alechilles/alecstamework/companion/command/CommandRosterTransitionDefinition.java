package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionJsonCodec;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        json.add(
                "groupAdmission",
                PopulationGroupTransitionAdmissionJsonCodec.encode(
                        payload.groupAdmission()
                )
        );
        return json.toString();
    }

    @Override
    public CommandRosterTransitionRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
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
                PopulationGroupTransitionAdmissionJsonCodec.decode(
                        json.getAsJsonObject("groupAdmission")
                )
        );
    }
}


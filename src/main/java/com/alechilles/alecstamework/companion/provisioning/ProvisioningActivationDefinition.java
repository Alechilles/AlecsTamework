package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonActivation;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseJsonCodec;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one shared live operation definition for initial activation. */
public final class ProvisioningActivationDefinition
        implements OperationDefinition<ProvisioningActivationRequest> {
    public static final ProvisioningActivationDefinition INSTANCE =
            new ProvisioningActivationDefinition();
    public static final OperationKind KIND =
            new OperationKind("provisioning_activation");

    private ProvisioningActivationDefinition() {
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
    public Class<ProvisioningActivationRequest> payloadType() {
        return ProvisioningActivationRequest.class;
    }

    @Override
    public String encode(ProvisioningActivationRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty(
                "callerNamespace",
                payload.origin().callerNamespace()
        );
        json.addProperty("callerKey", payload.origin().callerKey());
        json.add(
                "groupAdmission",
                PopulationGroupTransitionAdmissionJsonCodec.encode(
                        payload.groupAdmission()
                )
        );
        json.addProperty(
                "targetAlias", payload.targetAlias().toString()
        );
        json.addProperty("targetWorldKey", payload.targetWorldKey());
        json.addProperty("spawnReceiptKey", payload.spawnReceiptKey());
        json.add(
                "timedActivation",
                payload.timedActivation() == null
                        ? null
                        : timed(payload.timedActivation())
        );
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public ProvisioningActivationRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        JsonElement timed = json.get("timedActivation");
        return new ProvisioningActivationRequest(
                new ProvisioningOrigin(
                        json.get("callerNamespace").getAsString(),
                        json.get("callerKey").getAsString()
                ),
                PopulationGroupTransitionAdmissionJsonCodec.decode(
                        json.getAsJsonObject("groupAdmission")
                ),
                NpcAlias.parse(
                        json.get("targetAlias").getAsString()
                ),
                json.get("targetWorldKey").getAsString(),
                json.get("spawnReceiptKey").getAsString(),
                timed == null || timed.isJsonNull()
                        ? null
                        : readTimed(timed.getAsJsonObject()),
                json.get("requestedAtMs").getAsLong()
        );
    }

    private JsonObject timed(TimedSummonActivation value) {
        JsonObject json = new JsonObject();
        json.addProperty(
                "ownerId", value.familyKey().ownerId().toString()
        );
        json.addProperty("familyId", value.familyKey().familyId());
        json.addProperty("slotId", value.slotId().toString());
        json.addProperty(
                "expectedMembershipRevision",
                value.expectedMembershipRevision()
        );
        json.add("lease", TimedSummonLeaseJsonCodec.encode(
                value.lease()
        ));
        return json;
    }

    private TimedSummonActivation readTimed(JsonObject json) {
        return new TimedSummonActivation(
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
                TimedSummonLeaseJsonCodec.decode(
                        json.getAsJsonObject("lease")
                )
        );
    }
}

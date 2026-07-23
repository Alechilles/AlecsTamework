package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionJsonCodec;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Sole shared live operation definition for timed summon and store. */
public final class TimedSummonTransitionDefinition
        implements OperationDefinition<TimedSummonTransitionRequest> {
    public static final TimedSummonTransitionDefinition INSTANCE =
            new TimedSummonTransitionDefinition();
    public static final OperationKind KIND =
            new OperationKind("timed_summon_transition");

    private TimedSummonTransitionDefinition() {
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
    public Class<TimedSummonTransitionRequest> payloadType() {
        return TimedSummonTransitionRequest.class;
    }

    @Override
    public String encode(TimedSummonTransitionRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("action", payload.action().name());
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
                "beforeLease",
                TimedSummonLeaseJsonCodec.encode(payload.beforeLease())
        );
        json.add(
                "afterLease",
                TimedSummonLeaseJsonCodec.encode(payload.afterLease())
        );
        json.add(
                "groupAdmission",
                PopulationGroupTransitionAdmissionJsonCodec.encode(
                        payload.groupAdmission()
                )
        );
        json.addProperty("liveAlias", payload.liveAlias().toString());
        json.addProperty("worldKey", payload.worldKey());
        json.add(
                "snapshot",
                CompanionSnapshotJsonCodec.encode(payload.snapshot())
        );
        json.addProperty("receiptKey", payload.receiptKey());
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public TimedSummonTransitionRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        return new TimedSummonTransitionRequest(
                TimedSummonTransitionRequest.Action.valueOf(
                        json.get("action").getAsString()
                ),
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
                        json.getAsJsonObject("beforeLease")
                ),
                TimedSummonLeaseJsonCodec.decode(
                        json.getAsJsonObject("afterLease")
                ),
                PopulationGroupTransitionAdmissionJsonCodec.decode(
                        json.getAsJsonObject("groupAdmission")
                ),
                NpcAlias.parse(json.get("liveAlias").getAsString()),
                json.get("worldKey").getAsString(),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("snapshot")
                ),
                json.get("receiptKey").getAsString(),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

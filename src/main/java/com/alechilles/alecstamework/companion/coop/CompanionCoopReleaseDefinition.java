package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacementJsonCodec;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotJsonCodec;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Typed operation definition for coop-to-live release. */
public final class CompanionCoopReleaseDefinition
        implements OperationDefinition<CompanionCoopReleaseRequest> {
    public static final CompanionCoopReleaseDefinition INSTANCE =
            new CompanionCoopReleaseDefinition();
    public static final OperationKind KIND =
            new OperationKind("companion_coop_release");

    private CompanionCoopReleaseDefinition() {
    }

    @Override
    public OperationKind kind() {
        return KIND;
    }

    @Override
    public int payloadVersion() {
        return 2;
    }

    @Override
    public Class<CompanionCoopReleaseRequest> payloadType() {
        return CompanionCoopReleaseRequest.class;
    }

    @Override
    public String encode(CompanionCoopReleaseRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        json.add("sourceResidency", encodeResidency(payload.sourceResidency()));
        json.add(
                "sourceSnapshot",
                CompanionSnapshotJsonCodec.encode(payload.sourceSnapshot())
        );
        json.addProperty("targetAlias", payload.targetAlias().toString());
        json.add(
                "placement",
                CompanionSpawnPlacementJsonCodec.encode(payload.placement())
        );
        json.addProperty("spawnReceiptKey", payload.spawnReceiptKey());
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CompanionCoopReleaseRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionCoopReleaseRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                decodeResidency(json.getAsJsonObject("sourceResidency")),
                CompanionSnapshotJsonCodec.decode(
                        json.getAsJsonObject("sourceSnapshot")
                ),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                CompanionSpawnPlacementJsonCodec.decode(
                        json.getAsJsonObject("placement")
                ),
                json.get("spawnReceiptKey").getAsString(),
                json.get("requestedAtMs").getAsLong()
        );
    }

    private JsonObject encodeResidency(CoopResidency residency) {
        JsonObject json = new JsonObject();
        json.addProperty("slotKey", residency.slotKey().toString());
        json.addProperty("profileId", residency.profileId().toString());
        if (residency.housedNpcAlias() == null) {
            json.add("housedNpcAlias", null);
        } else {
            json.addProperty(
                    "housedNpcAlias", residency.housedNpcAlias().toString()
            );
        }
        json.addProperty("snapshotId", residency.snapshotId().toString());
        json.addProperty("capturedAtMs", residency.capturedAtMs());
        json.addProperty("updatedAtMs", residency.updatedAtMs());
        return json;
    }

    private CoopResidency decodeResidency(JsonObject json) {
        JsonElement alias = json.get("housedNpcAlias");
        return new CoopResidency(
                CoopSlotKey.parse(json.get("slotKey").getAsString()),
                ProfileId.parse(json.get("profileId").getAsString()),
                alias == null || alias.isJsonNull()
                        ? null
                        : NpcAlias.parse(alias.getAsString()),
                SnapshotId.parse(json.get("snapshotId").getAsString()),
                json.get("capturedAtMs").getAsLong(),
                json.get("updatedAtMs").getAsLong()
        );
    }
}

package com.alechilles.alecstamework.companion.progression;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one definition for a fully fenced saved-companion talent mutation. */
public final class SavedCompanionTalentDefinition
        implements OperationDefinition<SavedCompanionTalentRequest> {
    public static final SavedCompanionTalentDefinition INSTANCE =
            new SavedCompanionTalentDefinition();
    public static final OperationKind KIND =
            new OperationKind("saved_companion_talent");

    private SavedCompanionTalentDefinition() {
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
    public Class<SavedCompanionTalentRequest> payloadType() {
        return SavedCompanionTalentRequest.class;
    }

    @Override
    public String encode(SavedCompanionTalentRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("profileId", request.profileId().toString());
        json.addProperty("ownerId", request.ownerId().toString());
        json.addProperty("expectedLifecycleRevision",
                request.expectedLifecycleRevision().value());
        json.addProperty("expectedSnapshotId",
                request.expectedSnapshotId().toString());
        json.addProperty("expectedSnapshotHash",
                request.expectedSnapshotHash().toString());
        json.addProperty("action", request.action().name());
        if (request.talentId() == null) {
            json.add("talentId", null);
        } else {
            json.addProperty("talentId", request.talentId());
        }
        json.addProperty("expectedTalentConfigId",
                request.expectedTalentConfigId());
        json.addProperty("requestedAtMs", request.requestedAtMs());
        return json.toString();
    }

    @Override
    public SavedCompanionTalentRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        JsonElement talentId = json.get("talentId");
        return new SavedCompanionTalentRequest(
                ProfileId.parse(json.get("profileId").getAsString()),
                OwnerId.parse(json.get("ownerId").getAsString()),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()),
                SnapshotId.parse(json.get("expectedSnapshotId").getAsString()),
                Sha256Hash.parse(json.get("expectedSnapshotHash").getAsString()),
                SavedCompanionTalentRequest.Action.valueOf(
                        json.get("action").getAsString()),
                talentId == null || talentId.isJsonNull()
                        ? null : talentId.getAsString(),
                json.get("expectedTalentConfigId").getAsString(),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

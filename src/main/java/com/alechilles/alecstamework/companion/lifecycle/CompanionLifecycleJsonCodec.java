package com.alechilles.alecstamework.companion.lifecycle;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Validating JSON translation for canonical lifecycle operation evidence. */
public final class CompanionLifecycleJsonCodec {
    private CompanionLifecycleJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(@Nonnull CompanionLifecycle lifecycle) {
        if (lifecycle == null) {
            throw new IllegalArgumentException("Companion lifecycle is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", lifecycle.profileId().toString());
        nullable(json, "ownerId", lifecycle.ownerId());
        json.addProperty("state", lifecycle.state().name());
        json.addProperty("locationKind", lifecycle.location().kind().name());
        nullable(json, "locationKey", lifecycle.location().key());
        nullable(json, "worldKey", lifecycle.location().worldKey());
        nullable(json, "ownerWorldKey", lifecycle.ownerWorldKey());
        json.addProperty("revision", lifecycle.revision().value());
        nullable(json, "activeOperationId", lifecycle.activeOperationId());
        json.addProperty("stateChangedAtMs", lifecycle.stateChangedAtMs());
        json.addProperty(
                "lastReconciledGeneration",
                lifecycle.lastReconciledGeneration().value()
        );
        nullable(json, "quarantineIncidentId", lifecycle.quarantineIncidentId());
        return json;
    }

    @Nonnull
    public static CompanionLifecycle decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("Companion lifecycle JSON is required");
        }
        String owner = nullableText(json, "ownerId");
        String activeOperation = nullableText(json, "activeOperationId");
        String quarantine = nullableText(json, "quarantineIncidentId");
        String worldKey = nullableText(json, "worldKey");
        String ownerWorldKey = nullableText(json, "ownerWorldKey");
        if (owner != null && ownerWorldKey == null) {
            ownerWorldKey = worldKey;
        }
        return new CompanionLifecycle(
                ProfileId.parse(json.get("profileId").getAsString()),
                owner == null ? null : OwnerId.parse(owner),
                LifecycleState.valueOf(json.get("state").getAsString()),
                new LifecycleLocation(
                        LifecycleLocationKind.valueOf(
                                json.get("locationKind").getAsString()
                        ),
                        nullableText(json, "locationKey"),
                        worldKey
                ),
                new LifecycleRevision(json.get("revision").getAsLong()),
                activeOperation == null ? null : OperationId.parse(activeOperation),
                json.get("stateChangedAtMs").getAsLong(),
                new ReconciliationGeneration(
                        json.get("lastReconciledGeneration").getAsLong()
                ),
                quarantine == null ? null : IncidentId.parse(quarantine),
                ownerWorldKey
        );
    }

    private static void nullable(JsonObject json, String name, Object value) {
        if (value == null) {
            json.add(name, null);
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private static String nullableText(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}

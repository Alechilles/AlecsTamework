package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec for replayable owner transition outcomes. */
public final class OwnerPopulationTransitionEventCodec {
    public static final int VERSION = 1;

    private OwnerPopulationTransitionEventCodec() {
    }

    @Nonnull
    public static String encode(
            @Nonnull OwnerPopulationTransitionOutcome outcome
    ) {
        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Population transition outcome is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty(
                "sourceRevision",
                outcome.sourceRevision().value()
        );
        json.addProperty(
                "committedRevision",
                outcome.committedRevision().value()
        );
        nullable(json, "ownerId", outcome.ownerId());
        nullable(json, "ownerWorldKey", outcome.ownerWorldKey());
        json.addProperty("updatedAtMs", outcome.updatedAtMs());
        return json.toString();
    }

    @Nonnull
    public static OwnerPopulationTransitionOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported population transition event payload"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        String owner = text(json, "ownerId");
        return new OwnerPopulationTransitionOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("sourceRevision").getAsLong()
                ),
                new LifecycleRevision(
                        json.get("committedRevision").getAsLong()
                ),
                owner == null ? null : OwnerId.parse(owner),
                text(json, "ownerWorldKey"),
                json.get("updatedAtMs").getAsLong()
        );
    }

    private static void nullable(
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

    private static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }
}

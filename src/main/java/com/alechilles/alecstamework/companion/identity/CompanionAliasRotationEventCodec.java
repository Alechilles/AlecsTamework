package com.alechilles.alecstamework.companion.identity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec for replayable current-alias projection evidence. */
public final class CompanionAliasRotationEventCodec {
    public static final int VERSION = 1;

    private CompanionAliasRotationEventCodec() {
    }

    @Nonnull
    public static String encode(@Nonnull CompanionAliasRotationOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Alias rotation outcome is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty("currentAlias", outcome.currentAlias().toString());
        json.addProperty("generation", outcome.generation());
        json.addProperty("promotedAtMs", outcome.promotedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionAliasRotationOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException("alias_event_payload_version_unsupported");
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionAliasRotationOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                NpcAlias.parse(json.get("currentAlias").getAsString()),
                json.get("generation").getAsLong(),
                json.get("promotedAtMs").getAsLong()
        );
    }
}

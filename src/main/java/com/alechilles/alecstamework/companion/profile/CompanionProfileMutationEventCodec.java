package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec for replayable profile mutation outcomes. */
public final class CompanionProfileMutationEventCodec {
    public static final int VERSION = 1;

    private CompanionProfileMutationEventCodec() {
    }

    @Nonnull
    public static String encode(@Nonnull CompanionProfileMutationOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Profile mutation outcome is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("status", outcome.status().name());
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty("metadataRevision", outcome.metadataRevision());
        json.addProperty("updatedAtMs", outcome.updatedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionProfileMutationOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException("profile_event_payload_version_unsupported");
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionProfileMutationOutcome(
                CompanionProfileMutationOutcome.Status.valueOf(
                        json.get("status").getAsString()
                ),
                ProfileId.parse(json.get("profileId").getAsString()),
                json.get("metadataRevision").getAsLong(),
                json.get("updatedAtMs").getAsLong()
        );
    }
}

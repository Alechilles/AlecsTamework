package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec for replayable extension mutation outcomes. */
public final class ProfileExtensionMutationEventCodec {
    public static final int VERSION = 1;

    private ProfileExtensionMutationEventCodec() {
    }

    /** Encodes one durable domain outcome with deterministic field order. */
    @Nonnull
    public static String encode(@Nonnull ProfileExtensionMutationOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Extension mutation outcome is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("status", outcome.status().name());
        json.addProperty("profileId", outcome.key().profileId().toString());
        json.addProperty("namespace", outcome.key().namespace());
        json.addProperty("dataKey", outcome.key().dataKey());
        json.addProperty("revision", outcome.revision());
        if (outcome.jsonPayload() == null) {
            json.add("jsonPayload", null);
        } else {
            json.addProperty("jsonPayload", outcome.jsonPayload());
        }
        json.addProperty("updatedAtMs", outcome.updatedAtMs());
        return json.toString();
    }

    /** Decodes one exact supported event payload. */
    @Nonnull
    public static ProfileExtensionMutationOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException("extension_event_payload_version_unsupported");
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        JsonElement payload = json.get("jsonPayload");
        return new ProfileExtensionMutationOutcome(
                ProfileExtensionMutationOutcome.Status.valueOf(
                        json.get("status").getAsString()
                ),
                new ProfileExtensionKey(
                        ProfileId.parse(json.get("profileId").getAsString()),
                        json.get("namespace").getAsString(),
                        json.get("dataKey").getAsString()
                ),
                json.get("revision").getAsLong(),
                payload == null || payload.isJsonNull() ? null : payload.getAsString(),
                json.get("updatedAtMs").getAsLong()
        );
    }
}

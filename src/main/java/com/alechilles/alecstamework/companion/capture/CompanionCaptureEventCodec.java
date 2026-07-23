package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Versioned JSON codec for durable companion-capture result events. */
public final class CompanionCaptureEventCodec {
    public static final int VERSION = 1;

    private CompanionCaptureEventCodec() {
    }

    @Nonnull
    public static String encode(@Nonnull CompanionCaptureOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Companion capture outcome is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty("snapshotId", outcome.snapshotId().toString());
        json.addProperty(
                "lifecycleRevision",
                outcome.lifecycleRevision().value()
        );
        json.addProperty("sourceReceiptKey", outcome.sourceReceiptKey());
        json.addProperty("capturedAtMs", outcome.capturedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionCaptureOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "companion_capture_event_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionCaptureOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                SnapshotId.parse(json.get("snapshotId").getAsString()),
                new LifecycleRevision(
                        json.get("lifecycleRevision").getAsLong()
                ),
                json.get("sourceReceiptKey").getAsString(),
                json.get("capturedAtMs").getAsLong()
        );
    }
}

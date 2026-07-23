package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec for durable coop capture result evidence. */
public final class CompanionCoopCaptureEventCodec {
    public static final int VERSION = 1;

    private CompanionCoopCaptureEventCodec() {
    }

    @Nonnull
    public static String encode(@Nonnull CompanionCoopCaptureOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Coop capture outcome is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty("slotKey", outcome.slotKey().toString());
        json.addProperty("snapshotId", outcome.snapshotId().toString());
        json.addProperty(
                "lifecycleRevision", outcome.lifecycleRevision().value()
        );
        json.addProperty("slotRevision", outcome.slotRevision());
        json.addProperty(
                "retirementReceiptKey", outcome.retirementReceiptKey()
        );
        json.addProperty("capturedAtMs", outcome.capturedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionCoopCaptureOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "coop_capture_payload_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionCoopCaptureOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                CoopSlotKey.parse(json.get("slotKey").getAsString()),
                SnapshotId.parse(json.get("snapshotId").getAsString()),
                new LifecycleRevision(
                        json.get("lifecycleRevision").getAsLong()
                ),
                json.get("slotRevision").getAsLong(),
                json.get("retirementReceiptKey").getAsString(),
                json.get("capturedAtMs").getAsLong()
        );
    }
}

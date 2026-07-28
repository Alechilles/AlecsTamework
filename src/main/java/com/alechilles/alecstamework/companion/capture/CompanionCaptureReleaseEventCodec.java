package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Versioned JSON codec for durable captured-artifact release result events. */
public final class CompanionCaptureReleaseEventCodec {
    public static final int VERSION = 1;

    private CompanionCaptureReleaseEventCodec() {
    }

    @Nonnull
    public static String encode(
            @Nonnull CompanionCaptureReleaseOutcome outcome
    ) {
        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Captured-artifact release outcome is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty(
                "sourceSnapshotId",
                outcome.sourceSnapshotId().toString()
        );
        json.addProperty("targetAlias", outcome.targetAlias().toString());
        json.addProperty("targetWorldKey", outcome.targetWorldKey());
        json.addProperty(
                "lifecycleRevision",
                outcome.lifecycleRevision().value()
        );
        json.addProperty(
                "inventoryReceiptKey",
                outcome.inventoryReceiptKey()
        );
        json.addProperty("spawnReceiptKey", outcome.spawnReceiptKey());
        json.addProperty("releasedAtMs", outcome.releasedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionCaptureReleaseOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "companion_capture_release_event_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionCaptureReleaseOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                SnapshotId.parse(json.get("sourceSnapshotId").getAsString()),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                json.get("targetWorldKey").getAsString(),
                new LifecycleRevision(
                        json.get("lifecycleRevision").getAsLong()
                ),
                json.get("inventoryReceiptKey").getAsString(),
                json.get("spawnReceiptKey").getAsString(),
                json.get("releasedAtMs").getAsLong()
        );
    }
}

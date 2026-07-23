package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Versioned JSON codec for durable companion-restoration result events. */
public final class CompanionRestorationEventCodec {
    public static final int VERSION = 1;

    private CompanionRestorationEventCodec() {
    }

    @Nonnull
    public static String encode(@Nonnull CompanionRestorationOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Restoration outcome is required");
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
        json.addProperty("spawnReceiptKey", outcome.spawnReceiptKey());
        json.addProperty("restoredAtMs", outcome.restoredAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionRestorationOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "companion_restoration_event_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionRestorationOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                SnapshotId.parse(json.get("sourceSnapshotId").getAsString()),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                json.get("targetWorldKey").getAsString(),
                new LifecycleRevision(
                        json.get("lifecycleRevision").getAsLong()
                ),
                json.get("spawnReceiptKey").getAsString(),
                json.get("restoredAtMs").getAsLong()
        );
    }
}

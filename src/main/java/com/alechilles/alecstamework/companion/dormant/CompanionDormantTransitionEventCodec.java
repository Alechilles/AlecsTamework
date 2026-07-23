package com.alechilles.alecstamework.companion.dormant;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Versioned JSON codec for durable death-or-lost result events. */
public final class CompanionDormantTransitionEventCodec {
    public static final int VERSION = 1;

    private CompanionDormantTransitionEventCodec() {
    }

    @Nonnull
    public static String encode(@Nonnull CompanionDormantTransitionOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Dormant transition outcome is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty("state", outcome.state().name());
        json.addProperty("snapshotId", outcome.snapshotId().toString());
        json.addProperty(
                "lifecycleRevision",
                outcome.lifecycleRevision().value()
        );
        json.addProperty("sourceReceiptKey", outcome.sourceReceiptKey());
        json.addProperty("transitionedAtMs", outcome.transitionedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionDormantTransitionOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "companion_dormant_event_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionDormantTransitionOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                LifecycleState.valueOf(json.get("state").getAsString()),
                SnapshotId.parse(json.get("snapshotId").getAsString()),
                new LifecycleRevision(
                        json.get("lifecycleRevision").getAsLong()
                ),
                json.get("sourceReceiptKey").getAsString(),
                json.get("transitionedAtMs").getAsLong()
        );
    }
}

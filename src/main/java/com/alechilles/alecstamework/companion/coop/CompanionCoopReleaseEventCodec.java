package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec for durable coop release result evidence. */
public final class CompanionCoopReleaseEventCodec {
    public static final int VERSION = 1;

    private CompanionCoopReleaseEventCodec() {
    }

    @Nonnull
    public static String encode(@Nonnull CompanionCoopReleaseOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("Coop release outcome is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty("sourceSlot", outcome.sourceSlot().toString());
        json.addProperty(
                "sourceSnapshotId", outcome.sourceSnapshotId().toString()
        );
        json.addProperty("targetAlias", outcome.targetAlias().toString());
        json.addProperty("targetWorldKey", outcome.targetWorldKey());
        json.addProperty(
                "lifecycleRevision", outcome.lifecycleRevision().value()
        );
        json.addProperty("slotRevision", outcome.slotRevision());
        json.addProperty("spawnReceiptKey", outcome.spawnReceiptKey());
        json.addProperty("releasedAtMs", outcome.releasedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionCoopReleaseOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "coop_release_payload_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionCoopReleaseOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                CoopSlotKey.parse(json.get("sourceSlot").getAsString()),
                SnapshotId.parse(json.get("sourceSnapshotId").getAsString()),
                NpcAlias.parse(json.get("targetAlias").getAsString()),
                json.get("targetWorldKey").getAsString(),
                new LifecycleRevision(
                        json.get("lifecycleRevision").getAsLong()
                ),
                json.get("slotRevision").getAsLong(),
                json.get("spawnReceiptKey").getAsString(),
                json.get("releasedAtMs").getAsLong()
        );
    }
}

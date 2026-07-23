package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec and stream identity for self-contained coop occupancy projections. */
public final class CoopResidencyProjectionCodec {
    public static final int VERSION = 1;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("coop_residency_changed");

    private CoopResidencyProjectionCodec() {
    }

    @Nonnull
    public static String aggregateId(@Nonnull CoopSlotKey slotKey) {
        if (slotKey == null) {
            throw new IllegalArgumentException("Coop slot key is required");
        }
        return "coop-residency:" + slotKey;
    }

    @Nonnull
    public static String encode(@Nonnull CoopResidencyProjectionChange change) {
        if (change == null) {
            throw new IllegalArgumentException("Coop projection change is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("slotKey", change.slotKey().toString());
        json.addProperty("slotRevision", change.slotRevision());
        json.add("before", encodeResidency(change.before()));
        json.add("after", encodeResidency(change.after()));
        json.addProperty("changedAtMs", change.changedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CoopResidencyProjectionChange decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "coop_projection_payload_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CoopResidencyProjectionChange(
                CoopSlotKey.parse(json.get("slotKey").getAsString()),
                json.get("slotRevision").getAsLong(),
                decodeResidency(json.get("before")),
                decodeResidency(json.get("after")),
                json.get("changedAtMs").getAsLong()
        );
    }

    private static JsonElement encodeResidency(CoopResidency residency) {
        if (residency == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("slotKey", residency.slotKey().toString());
        json.addProperty("profileId", residency.profileId().toString());
        if (residency.housedNpcAlias() == null) {
            json.add("housedNpcAlias", null);
        } else {
            json.addProperty(
                    "housedNpcAlias", residency.housedNpcAlias().toString()
            );
        }
        json.addProperty("snapshotId", residency.snapshotId().toString());
        json.addProperty("capturedAtMs", residency.capturedAtMs());
        json.addProperty("updatedAtMs", residency.updatedAtMs());
        return json;
    }

    private static CoopResidency decodeResidency(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        JsonElement alias = json.get("housedNpcAlias");
        return new CoopResidency(
                CoopSlotKey.parse(json.get("slotKey").getAsString()),
                ProfileId.parse(json.get("profileId").getAsString()),
                alias == null || alias.isJsonNull()
                        ? null
                        : NpcAlias.parse(alias.getAsString()),
                SnapshotId.parse(json.get("snapshotId").getAsString()),
                json.get("capturedAtMs").getAsLong(),
                json.get("updatedAtMs").getAsLong()
        );
    }
}

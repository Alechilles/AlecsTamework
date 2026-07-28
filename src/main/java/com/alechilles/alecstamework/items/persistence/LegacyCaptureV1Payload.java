package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Strict decoder for the capture payload shipped by public persistence through 2.16.1.
 *
 * <p>The released payload intentionally contains no NPC UUID or component state. Those values
 * lived in the profile/link tables and exact filled item respectively.</p>
 */
record LegacyCaptureV1Payload(
        @Nullable SnapshotVector3 lastKnownPosition,
        @Nullable SnapshotVector3 homePosition,
        long capturedAtMs,
        @Nullable String roleId,
        @Nullable String displayName
) {
    static final int VERSION = 1;

    @Nonnull
    static LegacyCaptureV1Payload decode(
            @Nonnull CompanionSnapshot snapshot
    ) {
        if (snapshot == null
                || !CompanionCaptureRequest.SNAPSHOT_KIND.equals(
                snapshot.kind()
        )
                || snapshot.payloadVersion() != VERSION) {
            throw new IllegalArgumentException(
                    "Released-public capture-v1 snapshot is required"
            );
        }
        return decodePayloadJson(snapshot.payloadJson());
    }

    @Nonnull
    static LegacyCaptureV1Payload decodePayloadJson(
            @Nonnull String payloadJson
    ) {
        JsonObject json = LegacySnapshotJson.parseRoot(payloadJson);
        rejectReplacementShape(json);
        return new LegacyCaptureV1Payload(
                LegacySnapshotJson.optionalVector(
                        json, "lastKnownPosition"
                ),
                LegacySnapshotJson.optionalVector(json, "homePosition"),
                LegacySnapshotJson.requiredLong(json, "capturedAtMs"),
                LegacySnapshotJson.optionalString(json, "roleId"),
                LegacySnapshotJson.optionalString(json, "displayName")
        );
    }

    @Nonnull
    String encodePayloadJson() {
        JsonObject json = new JsonObject();
        LegacySnapshotJson.putVector(
                json, "lastKnownPosition", lastKnownPosition
        );
        LegacySnapshotJson.putVector(json, "homePosition", homePosition);
        json.addProperty("capturedAtMs", capturedAtMs);
        LegacySnapshotJson.putString(json, "roleId", roleId);
        LegacySnapshotJson.putString(json, "displayName", displayName);
        return json.toString();
    }

    private static void rejectReplacementShape(JsonObject json) {
        if (json.has("npcUuid") || json.has("version")
                || json.has("commandLinks") || json.has("owner")
                || json.has("tamed")) {
            throw new IllegalArgumentException(
                    "Replacement full-state payload cannot be labeled capture-v1"
            );
        }
    }

}

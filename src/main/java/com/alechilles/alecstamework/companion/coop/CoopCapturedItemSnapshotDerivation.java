package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Validates the only permitted captured-item snapshot transformation.
 *
 * <p>Captured full state is portable: it names no coop and uses resident slot {@code -1}. The
 * target snapshot may change only those two placement fields to the exact reserved physical
 * slot. Every gameplay component and capture timestamp remains byte-semantically identical.</p>
 */
final class CoopCapturedItemSnapshotDerivation {
    private CoopCapturedItemSnapshotDerivation() {
    }

    static void requireExact(
            CompanionSnapshot capture,
            CompanionSnapshot target,
            NpcAlias sourceAlias,
            CoopSlotKey targetSlot
    ) {
        JsonObject portable = object(
                capture.payloadJson(), "Captured-item source snapshot"
        );
        JsonObject housed = object(
                target.payloadJson(), "Captured-item target snapshot"
        );
        requireAlias(portable, sourceAlias, "source");
        requireAlias(housed, sourceAlias, "target");
        requirePortablePlacement(portable);
        requireTargetPlacement(housed, targetSlot);
        portable.remove("coopId");
        portable.remove("residentSlot");
        housed.remove("coopId");
        housed.remove("residentSlot");
        if (!portable.equals(housed)) {
            throw new IllegalArgumentException(
                    "Captured-item coop snapshot may change only its coop placement"
            );
        }
    }

    private static JsonObject object(String payload, String label) {
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(
                        label + " payload must be an object"
                );
            }
            return parsed.getAsJsonObject().deepCopy();
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argument) {
                throw argument;
            }
            throw new IllegalArgumentException(
                    label + " payload is invalid", failure
            );
        }
    }

    private static void requireAlias(
            JsonObject payload,
            NpcAlias sourceAlias,
            String label
    ) {
        JsonElement value = payload.get("npcUuid");
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || !sourceAlias.toString().equals(value.getAsString())) {
            throw new IllegalArgumentException(
                    "Captured-item coop " + label + " snapshot alias is not exact"
            );
        }
    }

    private static void requirePortablePlacement(JsonObject payload) {
        JsonElement coop = payload.get("coopId");
        JsonElement slot = payload.get("residentSlot");
        if (coop != null && !coop.isJsonNull()
                || slot == null || !slot.isJsonPrimitive()
                || !slot.getAsJsonPrimitive().isNumber()
                || slot.getAsBigDecimal().intValueExact() != -1) {
            throw new IllegalArgumentException(
                    "Captured-item source snapshot must be portable"
            );
        }
    }

    private static void requireTargetPlacement(
            JsonObject payload,
            CoopSlotKey targetSlot
    ) {
        JsonElement coop = payload.get("coopId");
        JsonElement slot = payload.get("residentSlot");
        if (coop == null || !coop.isJsonPrimitive()
                || !coop.getAsJsonPrimitive().isString()
                || !targetSlot.coopId().equals(coop.getAsString())
                || slot == null || !slot.isJsonPrimitive()
                || !slot.getAsJsonPrimitive().isNumber()
                || slot.getAsBigDecimal().intValueExact()
                != targetSlot.residentSlot()) {
            throw new IllegalArgumentException(
                    "Captured-item target snapshot must name the exact coop slot"
            );
        }
    }
}

package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.snapshot.SnapshotCodec;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import javax.annotation.Nonnull;

/**
 * Deterministic codec for the modern complete-state death envelope.
 *
 * <p>Decode fails closed when complete state or either death timestamp is absent. Version 1 is
 * the sole compatibility codec; version 2 never accepts an unreleased partial shape.</p>
 */
public final class DeathSnapshotV2Codec
        implements SnapshotCodec<DeathSnapshotV2Payload> {
    @Override
    @Nonnull
    public SnapshotKind kind() {
        return TameworkSnapshotCodecs.DEATH;
    }

    @Override
    public int version() {
        return 2;
    }

    @Override
    @Nonnull
    public Class<DeathSnapshotV2Payload> valueType() {
        return DeathSnapshotV2Payload.class;
    }

    @Override
    @Nonnull
    public String encode(@Nonnull DeathSnapshotV2Payload value) {
        if (value == null) {
            throw new IllegalArgumentException("Modern death snapshot is required");
        }
        JsonObject root = new JsonObject();
        root.add(
                "fullState",
                LegacySnapshotJson.parseRoot(value.fullStateJson())
        );
        root.addProperty("diedAtMs", value.diedAtMs());
        root.addProperty(
                "respawnAvailableAtMs",
                value.respawnAvailableAtMs()
        );
        root.addProperty(
                "deathCauseKind",
                value.deathCauseKind().name()
        );
        LegacySnapshotJson.putString(
                root,
                "deathSourceName",
                value.deathSourceName()
        );
        return root.toString();
    }

    @Override
    @Nonnull
    public DeathSnapshotV2Payload decode(@Nonnull String payloadJson) {
        JsonObject root = LegacySnapshotJson.parseRoot(payloadJson);
        JsonElement fullState = required(root, "fullState");
        if (!fullState.isJsonObject()) {
            throw invalid("fullState", "JSON object");
        }
        return new DeathSnapshotV2Payload(
                fullState.toString(),
                requiredLong(root, "diedAtMs"),
                requiredLong(root, "respawnAvailableAtMs"),
                requiredCause(root),
                LegacySnapshotJson.optionalString(root, "deathSourceName")
        );
    }

    private JsonElement required(JsonObject root, String field) {
        if (!root.has(field) || root.get(field).isJsonNull()) {
            throw invalid(field, "required value");
        }
        return root.get(field);
    }

    private long requiredLong(JsonObject root, String field) {
        JsonElement value = required(root, field);
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(field, "long");
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.longValueExact();
        } catch (RuntimeException failure) {
            throw invalid(field, "long");
        }
    }

    private DeathSnapshotV2Payload.DeathCauseKind requiredCause(
            JsonObject root
    ) {
        String raw = LegacySnapshotJson.optionalString(
                root,
                "deathCauseKind"
        );
        if (raw == null) {
            throw invalid("deathCauseKind", "released death cause");
        }
        try {
            return DeathSnapshotV2Payload.DeathCauseKind.valueOf(raw.trim());
        } catch (IllegalArgumentException failure) {
            throw invalid("deathCauseKind", "released death cause");
        }
    }

    private IllegalArgumentException invalid(String field, String expected) {
        return new IllegalArgumentException(
                "Invalid death snapshot field "
                        + field + ": expected " + expected
        );
    }
}

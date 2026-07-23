package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.snapshot.SnapshotCodec;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Strict deterministic codec for the released June lost snapshot payload. */
public final class LegacyLostV1SnapshotCodec implements SnapshotCodec<LegacyLostV1Payload> {
    @Override
    @Nonnull
    public SnapshotKind kind() {
        return TameworkSnapshotCodecs.LOST;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    @Nonnull
    public Class<LegacyLostV1Payload> valueType() {
        return LegacyLostV1Payload.class;
    }

    @Override
    @Nonnull
    public String encode(@Nonnull LegacyLostV1Payload value) {
        if (value == null) {
            throw new IllegalArgumentException("Legacy lost snapshot is required");
        }
        JsonObject root = new JsonObject();
        LegacySnapshotJson.putVector(root, "lastKnownPosition", value.lastKnownPosition());
        LegacySnapshotJson.putVector(root, "homePosition", value.homePosition());
        root.addProperty("lastRelocationQueuedAtMs", value.lastRelocationQueuedAtMs());
        root.addProperty("lostAtMs", value.lostAtMs());
        root.addProperty("relocationRetryAttempts", value.relocationRetryAttempts());
        LegacySnapshotJson.putUuid(root, "replacementNpcUuid", value.replacementNpcUuid());
        root.addProperty("recoveredAtMs", value.recoveredAtMs());
        return root.toString();
    }

    @Override
    @Nonnull
    public LegacyLostV1Payload decode(@Nonnull String payloadJson) {
        JsonObject root = LegacySnapshotJson.parseRoot(payloadJson);
        return new LegacyLostV1Payload(
                LegacySnapshotJson.optionalVector(root, "lastKnownPosition"),
                LegacySnapshotJson.optionalVector(root, "homePosition"),
                LegacySnapshotJson.optionalLong(root, "lastRelocationQueuedAtMs", 0L),
                LegacySnapshotJson.optionalLong(root, "lostAtMs", 0L),
                LegacySnapshotJson.optionalInt(root, "relocationRetryAttempts", 0),
                LegacySnapshotJson.optionalUuid(root, "replacementNpcUuid"),
                LegacySnapshotJson.optionalLong(root, "recoveredAtMs", 0L)
        );
    }
}

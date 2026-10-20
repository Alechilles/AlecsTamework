package com.alechilles.alecstamework.companion.store;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * One companion snapshot file (spec 6.5). Format 1 holds the BSON from
 * {@code EntityStore.REGISTRY.serialize}. Format 0 ({@link #FORMAT_IMPORTED_STATE}) holds the
 * state of a companion imported from 3.x or 4.x: {@code data} is {@code {Json: <string>}}, where
 * the string is the plain {@code CoopResidentStateSnapshot} JSON, exactly as
 * {@code CoopResidentStateSnapshotCodec} encodes it, with no death or bonded wrapper around it.
 * A format 0 snapshot is replaced by a format 1 one the first time its companion is restored.
 */
public record SnapshotEnvelope(@Nonnull UUID profileId, int format, long generation, @Nonnull BsonDocument data) {
    /** The format of an imported state snapshot. */
    public static final int FORMAT_IMPORTED_STATE = 0;
    private static final String IMPORTED_STATE_JSON = "Json";

    public SnapshotEnvelope {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(data, "data");
    }

    /** A format 0 envelope holding {@code stateJson}, the plain {@code CoopResidentStateSnapshot} JSON. */
    @Nonnull
    public static SnapshotEnvelope importedState(@Nonnull UUID profileId, long generation, @Nonnull String stateJson) {
        Objects.requireNonNull(stateJson, "stateJson");
        return new SnapshotEnvelope(profileId, FORMAT_IMPORTED_STATE, generation,
                new BsonDocument(IMPORTED_STATE_JSON, new BsonString(stateJson)));
    }

    /** The state JSON of a format 0 envelope; null for any other format or when the string is missing. */
    @Nullable
    public String importedStateJson() {
        return format == FORMAT_IMPORTED_STATE && data.isString(IMPORTED_STATE_JSON)
                ? data.getString(IMPORTED_STATE_JSON).getValue() : null;
    }

    @Nonnull
    public BsonDocument toBson() {
        return new BsonDocument("Format", new BsonInt32(format))
                .append("ProfileId", new BsonString(profileId.toString()))
                .append("Generation", new BsonInt64(generation))
                .append("Data", data);
    }

    @Nonnull
    public static SnapshotEnvelope fromBson(@Nonnull BsonDocument d) {
        return new SnapshotEnvelope(UUID.fromString(d.getString("ProfileId").getValue()),
                d.get("Format").asNumber().intValue(), d.get("Generation").asNumber().longValue(), d.getDocument("Data"));
    }
}

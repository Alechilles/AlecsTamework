package com.alechilles.alecstamework.companion.store;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * One companion snapshot file (spec 6.5). Format 1 holds the BSON from
 * {@code EntityStore.REGISTRY.serialize}; format 0 holds imported allow-list JSON
 * under {@code data.Json}.
 */
public record SnapshotEnvelope(@Nonnull UUID profileId, int format, long generation, @Nonnull BsonDocument data) {
    public SnapshotEnvelope {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(data, "data");
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

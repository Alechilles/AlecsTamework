package com.alechilles.alecstamework.companion.snapshot;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Validating JSON translation for immutable companion snapshot operation evidence. */
public final class CompanionSnapshotJsonCodec {
    private CompanionSnapshotJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(@Nonnull CompanionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Companion snapshot is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("snapshotId", snapshot.snapshotId().toString());
        json.addProperty("profileId", snapshot.profileId().toString());
        json.addProperty("kind", snapshot.kind().toString());
        json.addProperty("payloadVersion", snapshot.payloadVersion());
        json.addProperty("payloadJson", snapshot.payloadJson());
        json.addProperty("payloadHash", snapshot.payloadHash().toString());
        json.addProperty(
                "sourceLifecycleRevision",
                snapshot.sourceLifecycleRevision().value()
        );
        json.addProperty("current", snapshot.current());
        json.addProperty("createdAtMs", snapshot.createdAtMs());
        return json;
    }

    @Nonnull
    public static CompanionSnapshot decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("Companion snapshot JSON is required");
        }
        return new CompanionSnapshot(
                SnapshotId.parse(json.get("snapshotId").getAsString()),
                ProfileId.parse(json.get("profileId").getAsString()),
                new SnapshotKind(json.get("kind").getAsString()),
                json.get("payloadVersion").getAsInt(),
                json.get("payloadJson").getAsString(),
                Sha256Hash.parse(json.get("payloadHash").getAsString()),
                new LifecycleRevision(
                        json.get("sourceLifecycleRevision").getAsLong()
                ),
                json.get("current").getAsBoolean(),
                json.get("createdAtMs").getAsLong()
        );
    }
}

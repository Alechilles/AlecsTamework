package com.alechilles.alecstamework.items.persistence.checkpoint;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;

/** Encodes and validates exact full-entity checkpoints. */
public final class CompanionEntityCheckpointCodec {
    /** Returns the durable JSON including its integrity digest. */
    @Nonnull
    public String encode(@Nonnull CompanionEntityCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String material = integrityMaterial(checkpoint);
        if (!checkpoint.payloadHash().matchesUtf8(material)) {
            throw new IllegalArgumentException(
                    "Companion checkpoint hash does not match its body"
            );
        }
        JsonObject json = JsonParser.parseString(material).getAsJsonObject();
        json.addProperty("payloadHash", checkpoint.payloadHash().toString());
        return json.toString();
    }

    /** Parses a checkpoint and rejects corrupt or unsupported state. */
    @Nonnull
    public CompanionEntityCheckpoint decode(@Nonnull String encoded) {
        JsonObject json = JsonParser.parseString(
                Objects.requireNonNull(encoded, "encoded")
        ).getAsJsonObject();
        CompanionEntityCheckpoint checkpoint = new CompanionEntityCheckpoint(
                integer(json, "version"),
                ProfileId.parse(text(json, "profileId")),
                NpcAlias.parse(text(json, "alias")),
                NpcAlias.parse(text(json, "sourceAlias")),
                number(json, "aliasGeneration"),
                OwnerId.parse(text(json, "ownerId")),
                new LifecycleRevision(number(json, "lifecycleRevision")),
                new ReconciliationGeneration(number(
                        json, "reconciliationGeneration"
                )),
                text(json, "worldKey"),
                decimal(json, "x"),
                decimal(json, "y"),
                decimal(json, "z"),
                CompanionEntityCheckpoint.CaptureBoundary.valueOf(
                        text(json, "boundary")
                ),
                number(json, "capturedAtMs"),
                BsonDocument.parse(text(json, "holderExtendedJson")),
                Sha256Hash.parse(text(json, "payloadHash"))
        );
        if (!checkpoint.payloadHash().matchesUtf8(
                integrityMaterial(checkpoint)
        )) {
            throw new IllegalArgumentException(
                    "Companion checkpoint integrity check failed"
            );
        }
        return checkpoint;
    }

    /** Returns the canonical hash material without the digest field. */
    @Nonnull
    public String integrityMaterial(
            @Nonnull CompanionEntityCheckpoint checkpoint
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("version", checkpoint.version());
        json.addProperty("profileId", checkpoint.profileId().toString());
        json.addProperty("alias", checkpoint.alias().toString());
        json.addProperty(
                "sourceAlias", checkpoint.sourceAlias().toString()
        );
        json.addProperty("aliasGeneration", checkpoint.aliasGeneration());
        json.addProperty("ownerId", checkpoint.ownerId().toString());
        json.addProperty(
                "lifecycleRevision", checkpoint.lifecycleRevision().value()
        );
        json.addProperty(
                "reconciliationGeneration",
                checkpoint.reconciliationGeneration().value()
        );
        json.addProperty("worldKey", checkpoint.worldKey());
        json.addProperty("x", checkpoint.x());
        json.addProperty("y", checkpoint.y());
        json.addProperty("z", checkpoint.z());
        json.addProperty("boundary", checkpoint.boundary().name());
        json.addProperty("capturedAtMs", checkpoint.capturedAtMs());
        json.addProperty(
                "holderExtendedJson", checkpoint.holder().toJson()
        );
        return json.toString();
    }

    private static String text(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException(
                    "Checkpoint field is missing: " + field
            );
        }
        return json.get(field).getAsString();
    }

    private static int integer(JsonObject json, String field) {
        return Math.toIntExact(number(json, field));
    }

    private static long number(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException(
                    "Checkpoint number is missing: " + field
            );
        }
        return json.get(field).getAsLong();
    }

    private static double decimal(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException(
                    "Checkpoint decimal is missing: " + field
            );
        }
        return json.get(field).getAsDouble();
    }
}
